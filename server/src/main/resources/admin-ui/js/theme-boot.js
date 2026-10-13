// Applies the saved theme before the first paint (a classic script in <head>, so the page never flashes the wrong colours).
(function () {
  try {
    var saved = window.localStorage.getItem('flash.hub.theme');
    if (saved === 'light' || saved === 'dark') document.documentElement.setAttribute('data-theme', saved);
  } catch (e) {
    /* storage blocked: the system theme applies */
  }
})();
