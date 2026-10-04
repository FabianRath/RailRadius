/** Lets the user fold the side panel away so the map gets the whole screen. */
export function initSidebarToggle() {
    const container = document.getElementById('container');
    const hideButton = document.getElementById('sidebarHide');
    const showButton = document.getElementById('sidebarShow');

    function setCollapsed(collapsed) {
        container.classList.toggle('sidebar-collapsed', collapsed);
        hideButton.setAttribute('aria-expanded', String(!collapsed));
        showButton.setAttribute('aria-expanded', String(!collapsed));
        (collapsed ? showButton : hideButton).focus();
    }

    hideButton.addEventListener('click', () => setCollapsed(true));
    showButton.addEventListener('click', () => setCollapsed(false));
}
