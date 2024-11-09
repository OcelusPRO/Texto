// theme.js
const themeToggle = document.querySelector('[data-theme-toggle]');
const html =

    document.documentElement;

function setTheme(theme) {
    if (theme === 'dark') {
        html.classList.add('dark');
        themeToggle.innerHTML = '<i class="fas fa-moon text-2xl"></i>';
    } else {
        html.classList.remove('dark');
        themeToggle.innerHTML = '<i class="fas fa-sun text-2xl"></i>';
    }
    localStorage.setItem('theme', theme);
}

// Check for saved theme preference or use system preference
const savedTheme = localStorage.getItem('theme');
if (savedTheme) {
    setTheme(savedTheme);
} else if (window.matchMedia('(prefers-color-scheme: dark)').matches) {
    setTheme('dark');
}

themeToggle.addEventListener('click', () => {
    const currentTheme = html.classList.contains('dark') ? 'light' : 'dark';
    setTheme(currentTheme);
});