"use strict";

function showInvite() {
  const open = document.getElementById("open");
  const copy = document.getElementById("copy");
  open.hidden = true;
  open.removeAttribute("href");
  copy.hidden = true;
  document.getElementById("title").textContent = "Open your Flash invite";
  const group = location.pathname === "/invite/group";
  const path = group ? "/invite/group" : "/invite/enroll";
  const fragment = location.hash.slice(1);
  const status = document.getElementById("status");
  let invite = "";
  if (location.pathname === path && !location.search && location.href.length <= 4096 &&
      /^[A-Za-z0-9_-]+$/.test(fragment)) {
    try {
      const bytes = Uint8Array.from(atob(fragment.replaceAll("-", "+").replaceAll("_", "/")), ch => ch.charCodeAt(0));
      const decoded = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
      const format = group ? /^flash:\/\/g\/1\/[A-Za-z0-9_-]+$/ : /^flash:\/\/enroll\?[^\s\x00-\x1f\x7f#]+$/;
      if (format.test(decoded)) invite = decoded;
    } catch {
      // A malformed invitation has no open or copy action.
    }
  }
  if (invite) {
    document.getElementById("title").textContent = group ? "Join a Flash group" : "Enrol in an organisation";
    status.textContent = "";
    open.href = invite;
    open.hidden = false;
    copy.hidden = !navigator.clipboard;
    copy.onclick = async () => {
      try {
        await navigator.clipboard.writeText(location.href);
        status.textContent = "Invite link copied.";
      } catch {
        status.textContent = "Copy the invite link from the address bar.";
      }
    };
  } else {
    status.textContent = "This link is incomplete or invalid. Ask the sender for a fresh invite.";
  }
}
window.addEventListener("hashchange", showInvite);
showInvite();
