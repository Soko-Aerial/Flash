// Copy-link button of the invite landing page. No network access, no storage: it only copies the link the page already shows.
const button = document.getElementById('copy-link');
const status = document.getElementById('copy-status');

async function copy(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch (e) {
    /* fall back below */
  }
  try {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.className = 'offscreen';
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand('copy');
    area.remove();
    return ok;
  } catch (e) {
    return false;
  }
}

if (button && status) {
  button.addEventListener('click', async () => {
    const ok = await copy(button.getAttribute('data-link') || '');
    status.textContent = ok ? 'Link copied.' : 'Copy failed. Open the link instead.';
  });
}
