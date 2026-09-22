# Scenario 3: Open Protocol Specification & Multi-Platform Client Samples

The Flash wire protocol is completely open, transparent, and platform-independent. Any language or operating system that can open a TCP/WebSocket connection can discover Flash devices, exchange end-to-end encrypted messages, and stream high-speed binary file transfers.

---

## 1. Wire Protocol Specification

Flash connections run over standard WebSockets (`ws://` or `wss://`). The wire format distinguishes two types of frames:
1. **Text Frames (Control & Messaging):** UTF-8 strings formatted as `COMMAND key1=val1 key2=val2 ...`.
2. **Binary Frames (File Chunks & Media):** A fixed 24-byte binary header followed immediately by the raw chunk bytes.

### 1.1 Handshake & Identification
Upon connecting, the client transmits an initial hello frame:
```text
FLASH_HELLO version=1 deviceId=<unique_id> displayName=<name> os=linux
```
The peer responds with its own `FLASH_HELLO`.

### 1.2 Messaging Frames
* **Send Message:** `FLASH_MSG id=<uuid> body=<utf8_text>`
* **Delivery Receipt:** `FLASH_RCPT id=<uuid> status=delivered`
* **Read Receipt:** `FLASH_READ id=<uuid> readAt=<unix_ms>`
* **Emoji Reaction:** `FLASH_REACT id=<uuid> emoji=<unicode_char>`
* **Typing Indicator:** `FLASH_TYPING isTyping=true|false`

### 1.3 Transfer Negotiation & Chunk Control
1. **Transfer Offer (`FLASH_XFER action=offer`):**
   ```text
   FLASH_XFER action=offer transferId=<tid> fileId=<fid> fileName=<name> totalBytes=<bytes> [sha256=<hex>]
   ```
2. **Accept Offer (`FLASH_XFER action=accept`):**
   ```text
   FLASH_XFER action=accept transferId=<tid>
   ```
3. **Chunk Binary Frame Structure (24-byte Header):**
   ```text
   0                   1                   2                   3
   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |          Magic 'FL'           |          Version (1)          |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                         Chunk Index                           |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                       File Byte Offset                        |
   |                                                               |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                         Chunk Length                          |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                             Flags                             |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                     Raw Chunk Payload Data                    |
   |                              ...                              |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   ```
4. **Chunk Acknowledgment:**
   ```text
   FLASH_ACK transferId=<tid> index=<chunk_index>
   ```
5. **Transfer Completed:**
   ```text
   FLASH_XFER action=complete transferId=<tid> verified=true
   ```

---

## 2. Python Client Sample (`flash_client.py`)

A complete, runnable Python 3 script using `websockets` to connect to a Flash Android or Desktop app, complete the handshake, and send a text message:

```python
import asyncio
import websockets
import uuid

FLASH_PEER_HOST = "192.168.1.150"
FLASH_PEER_PORT = 49200

async def flash_client():
    uri = f"ws://{FLASH_PEER_HOST}:{FLASH_PEER_PORT}"
    print(f"Connecting to Flash peer at {uri}...")
    
    async with websockets.connect(uri) as ws:
        # 1. Send Handshake
        client_id = "python_node_01"
        await ws.send(f"FLASH_HELLO version=1 deviceId={client_id} displayName=Python-Agent os=linux")
        
        # 2. Receive Peer Handshake
        peer_hello = await ws.recv()
        print(f"Received from peer: {peer_hello}")
        
        # 3. Send a Chat Message
        msg_id = str(uuid.uuid4())
        msg_frame = f"FLASH_MSG id={msg_id} body=Hello from Python client!"
        await ws.send(msg_frame)
        print(f"Sent: {msg_frame}")
        
        # 4. Listen for incoming responses (Receipts, Messages, Transfers)
        while True:
            response = await ws.recv()
            if isinstance(response, str):
                print(f"[Text Frame]: {response}")
            else:
                print(f"[Binary Frame]: Received {len(response)} bytes of raw transfer data")

if __name__ == "__main__":
    asyncio.run(flash_client())
```

---

## 3. Rust Client Sample (`flash_peer.rs`)

A high-performance async Rust implementation using `tokio` and `tokio-tungstenite`:

```rust
use futures_util::{SinkExt, StreamExt};
use tokio_tungstenite::{connect_async, tungstenite::protocol::Message};
use url::Url;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let peer_url = Url::parse("ws://192.168.1.150:49200")?;
    let (ws_stream, _) = connect_async(peer_url).await?;
    println!("Connected to Flash peer!");

    let (mut write, mut read) = ws_stream.split();

    // 1. Send Handshake
    let hello = "FLASH_HELLO version=1 deviceId=rust_peer_01 displayName=Rust-Station os=linux";
    write.send(Message::Text(hello.into())).await?;

    // 2. Read incoming frames
    while let Some(msg) = read.next().await {
        match msg? {
            Message::Text(text) => {
                println!("[Incoming Text Frame]: {}", text);
                if text.starts_with("FLASH_HELLO") {
                    // Send chat message once peer hello is confirmed
                    let msg_frame = "FLASH_MSG id=rust_msg_101 body=Hello from Rust client!";
                    write.send(Message::Text(msg_frame.into())).await?;
                }
            }
            Message::Binary(bin) => {
                println!("[Incoming Binary Frame]: {} bytes chunk received", bin.len());
            }
            _ => {}
        }
    }

    Ok(())
}
```

---

## 4. Go Client Sample (`flash_client.go`)

A lightweight Go client using `gorilla/websocket`:

```go
package main

import (
	"fmt"
	"log"
	"github.com/gorilla/websocket"
)

func main() {
	url := "ws://192.168.1.150:49200"
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		log.Fatalf("Dial failed: %v", err)
	}
	defer conn.Close()

	// 1. Handshake
	hello := "FLASH_HELLO version=1 deviceId=go_client_01 displayName=Go-Daemon os=darwin"
	if err := conn.WriteMessage(websocket.TextMessage, []byte(hello)); err != nil {
		log.Fatalf("Write failed: %v", err)
	}

	// 2. Event loop
	for {
		messageType, p, err := conn.ReadMessage()
		if err != nil {
			log.Println("Read error:", err)
			return
		}
		if messageType == websocket.TextMessage {
			fmt.Printf("[Received Text]: %s\n", string(p))
		} else if messageType == websocket.BinaryMessage {
			fmt.Printf("[Received Binary Chunk]: %d bytes\n", len(p))
		}
	}
}
```
