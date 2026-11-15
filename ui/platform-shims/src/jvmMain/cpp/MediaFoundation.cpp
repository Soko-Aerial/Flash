#include <windows.h>
#include <d3d11.h>
#include <d3d10_1.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfmediaengine.h>
#include <wrl/client.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <new>

namespace {
using Microsoft::WRL::ComPtr;
constexpr UINT maxFrameEdge = 1280;
constexpr auto pixelFormat = DXGI_FORMAT_B8G8R8A8_UNORM;

class MediaRuntime {
public:
    HRESULT start() {
        HRESULT result = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        if (FAILED(result)) return result;
        comStarted = true;
        result = MFStartup(MF_VERSION);
        mediaStarted = SUCCEEDED(result);
        return result;
    }

    ~MediaRuntime() {
        if (mediaStarted) MFShutdown();
        if (comStarted) CoUninitialize();
    }

private:
    bool comStarted = false;
    bool mediaStarted = false;
};

class MediaNotify final : public IMFMediaEngineNotify {
public:
    STDMETHODIMP QueryInterface(REFIID id, void** output) override {
        if (!output) return E_POINTER;
        *output = nullptr;
        if (id != __uuidof(IUnknown) && id != __uuidof(IMFMediaEngineNotify) && id != __uuidof(IAgileObject)) {
            return E_NOINTERFACE;
        }
        *output = static_cast<IMFMediaEngineNotify*>(this);
        AddRef();
        return S_OK;
    }

    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&references); }

    STDMETHODIMP_(ULONG) Release() override {
        const auto remaining = InterlockedDecrement(&references);
        if (remaining == 0) delete this;
        return remaining;
    }

    STDMETHODIMP EventNotify(DWORD event, DWORD_PTR param, DWORD) override {
        if (event == MF_MEDIA_ENGINE_EVENT_NOTIFYSTABLESTATE) SetEvent(reinterpret_cast<HANDLE>(param));
        return S_OK;
    }

private:
    LONG references = 1;
};

struct Player {
    MediaRuntime runtime; // Released last, after every COM object.
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    ComPtr<IMFDXGIDeviceManager> manager;
    ComPtr<IMFMediaEngine> engine;
    ComPtr<ID3D11Texture2D> target;
    ComPtr<ID3D11Texture2D> staging;
    UINT width = 0;
    UINT height = 0;

    ~Player() { if (engine) engine->Shutdown(); }

    HRESULT open(const wchar_t* path) {
        HRESULT result = runtime.start();
        if (FAILED(result)) return result;
        result = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT, nullptr, 0,
            D3D11_SDK_VERSION, device.GetAddressOf(), nullptr, context.GetAddressOf());
        if (FAILED(result)) {
            result = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                nullptr, 0, D3D11_SDK_VERSION, device.ReleaseAndGetAddressOf(), nullptr, context.ReleaseAndGetAddressOf());
        }
        if (FAILED(result)) return result;
        ComPtr<ID3D10Multithread> threading;
        result = device.As(&threading);
        if (FAILED(result)) return result;
        threading->SetMultithreadProtected(TRUE);
        UINT resetToken;
        if (FAILED(result = MFCreateDXGIDeviceManager(&resetToken, manager.GetAddressOf()))) return result;
        if (FAILED(result = manager->ResetDevice(device.Get(), resetToken))) return result;

        ComPtr<IMFAttributes> attributes;
        if (FAILED(result = MFCreateAttributes(attributes.GetAddressOf(), 3))) return result;
        ComPtr<IMFMediaEngineNotify> notify;
        notify.Attach(new (std::nothrow) MediaNotify());
        if (!notify) return E_OUTOFMEMORY;
        if (FAILED(result = attributes->SetUnknown(MF_MEDIA_ENGINE_CALLBACK, notify.Get()))) return result;
        if (FAILED(result = attributes->SetUnknown(MF_MEDIA_ENGINE_DXGI_MANAGER, manager.Get()))) return result;
        if (FAILED(result = attributes->SetUINT32(MF_MEDIA_ENGINE_VIDEO_OUTPUT_FORMAT, pixelFormat))) return result;
        ComPtr<IMFMediaEngineClassFactory> factory;
        result = CoCreateInstance(CLSID_MFMediaEngineClassFactory, nullptr, CLSCTX_INPROC_SERVER,
            IID_PPV_ARGS(factory.GetAddressOf()));
        if (FAILED(result)) return result;
        if (FAILED(result = factory->CreateInstance(0, attributes.Get(), engine.GetAddressOf()))) return result;
        engine->SetPreload(MF_MEDIA_ENGINE_PRELOAD_AUTOMATIC);

        ComPtr<IMFByteStream> stream;
        result = MFCreateFile(MF_ACCESSMODE_READ, MF_OPENMODE_FAIL_IF_NOT_EXIST, MF_FILEFLAGS_NONE, path, stream.GetAddressOf());
        if (FAILED(result)) return result;
        ComPtr<IMFMediaEngineEx> engineEx;
        if (FAILED(result = engine.As(&engineEx))) return result;
        BSTR sourceName = SysAllocString(path);
        if (!sourceName) return E_OUTOFMEMORY;
        result = engineEx->SetSourceFromByteStream(stream.Get(), sourceName);
        SysFreeString(sourceName);
        return result;
    }

    HRESULT prepareTextures(UINT requestedWidth, UINT requestedHeight) {
        const auto longEdge = std::max(requestedWidth, requestedHeight);
        const auto scale = std::min(1.0, static_cast<double>(maxFrameEdge) / longEdge);
        const auto nextWidth = std::max(1u, static_cast<UINT>(std::lround(requestedWidth * scale)));
        const auto nextHeight = std::max(1u, static_cast<UINT>(std::lround(requestedHeight * scale)));
        if (nextWidth == width && nextHeight == height) return S_OK;

        D3D11_TEXTURE2D_DESC desc{};
        desc.Width = nextWidth;
        desc.Height = nextHeight;
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Format = pixelFormat;
        desc.SampleDesc.Count = 1;
        desc.BindFlags = D3D11_BIND_RENDER_TARGET;
        HRESULT result = device->CreateTexture2D(&desc, nullptr, target.ReleaseAndGetAddressOf());
        if (FAILED(result)) return result;
        desc.Usage = D3D11_USAGE_STAGING;
        desc.BindFlags = 0;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        result = device->CreateTexture2D(&desc, nullptr, staging.ReleaseAndGetAddressOf());
        if (FAILED(result)) return result;
        width = nextWidth;
        height = nextHeight;
        return S_OK;
    }
};

struct PlaybackStatus {
    std::int64_t durationMs;
    std::int64_t positionMs;
    std::uint32_t flags;
    std::uint32_t width;
    std::uint32_t height;
};
static_assert(offsetof(PlaybackStatus, flags) == 16 && offsetof(PlaybackStatus, height) == 24);

std::int64_t milliseconds(double seconds) {
    return std::isfinite(seconds) && seconds > 0 && seconds < 1e12
        ? static_cast<std::int64_t>(seconds * 1000) : 0;
}
} // namespace

extern "C" {
__declspec(dllexport) Player* flash_video_open(const wchar_t* path, HRESULT* result) {
    auto player = std::unique_ptr<Player>(new (std::nothrow) Player());
    *result = player ? player->open(path) : E_OUTOFMEMORY;
    return SUCCEEDED(*result) ? player.release() : nullptr;
}

__declspec(dllexport) HRESULT flash_video_control(Player* player, BOOL playing, BOOL muted, double seekSeconds) {
    auto& engine = player->engine;
    if (seekSeconds >= 0) {
        const HRESULT result = engine->SetCurrentTime(seekSeconds);
        if (FAILED(result)) return result;
    }
    HRESULT result = engine->SetMuted(muted);
    if (FAILED(result)) return result;
    return playing ? engine->Play() : engine->Pause();
}

__declspec(dllexport) HRESULT flash_video_poll(Player* player, PlaybackStatus* status, std::uint8_t* pixels, int capacity) {
    auto& engine = player->engine;
    ComPtr<IMFMediaError> error;
    if (SUCCEEDED(engine->GetError(error.GetAddressOf())) && error && error->GetErrorCode() != MF_MEDIA_ENGINE_ERR_NOERROR) {
        const HRESULT detail = error->GetExtendedErrorCode();
        return FAILED(detail) ? detail : E_FAIL;
    }
    const auto ready = engine->GetReadyState();
    if (ready >= MF_MEDIA_ENGINE_READY_HAVE_METADATA && !engine->HasVideo()) return MF_E_INVALIDMEDIATYPE;
    *status = {
        milliseconds(engine->GetDuration()), milliseconds(engine->GetCurrentTime()),
        (engine->IsEnded() ? 1u : 0u) | (engine->IsSeeking() || ready < MF_MEDIA_ENGINE_READY_HAVE_CURRENT_DATA ? 2u : 0u),
        0, 0,
    };
    LONGLONG pts;
    HRESULT result = engine->OnVideoStreamTick(&pts);
    if (result != S_OK) return ready < MF_MEDIA_ENGINE_READY_HAVE_CURRENT_DATA ? S_FALSE : result;

    DWORD nativeWidth = 0;
    DWORD nativeHeight = 0;
    result = engine->GetNativeVideoSize(&nativeWidth, &nativeHeight);
    if (FAILED(result)) return result;
    if (!nativeWidth || !nativeHeight) return S_FALSE;
    if (FAILED(result = player->prepareTextures(nativeWidth, nativeHeight))) return result;
    const auto rowBytes = player->width * 4;
    if (capacity < 0 || static_cast<UINT>(capacity) < rowBytes * player->height) return E_INVALIDARG;
    const RECT destination{0, 0, static_cast<LONG>(player->width), static_cast<LONG>(player->height)};
    const MFARGB black{0, 0, 0, 255};
    result = engine->TransferVideoFrame(player->target.Get(), nullptr, &destination, &black);
    if (FAILED(result)) return result;
    player->context->CopyResource(player->staging.Get(), player->target.Get());
    D3D11_MAPPED_SUBRESOURCE mapped{};
    result = player->context->Map(player->staging.Get(), 0, D3D11_MAP_READ, 0, &mapped);
    if (FAILED(result)) return result;
    const auto* source = static_cast<const std::uint8_t*>(mapped.pData);
    for (UINT row = 0; row < player->height; ++row) {
        std::memcpy(pixels + row * rowBytes, source + row * mapped.RowPitch, rowBytes);
    }
    player->context->Unmap(player->staging.Get(), 0);
    status->width = player->width;
    status->height = player->height;
    return S_OK;
}

__declspec(dllexport) void flash_video_close(Player* player) { delete player; }
}
