// Ajoutez cette variable au début du fichier
let originalCode = '';


document.addEventListener('DOMContentLoaded', (event) => {
    document.querySelectorAll('pre code').forEach((block) => {
        originalCode = block.textContent;
        hljs.highlightElement(block);
        //hljs.lineNumbersBlock(block);
    });
});

const themeToggle = document.querySelector('[data-theme-toggle]');
const html = document.documentElement;
const highlightLink = document.querySelector('link[href*="highlight.js"]');

function setTheme(theme) {
    if (theme === 'dark') {
        html.classList.add('dark');
        themeToggle.innerHTML = '<i class="fas fa-moon text-2xl"></i>';
        highlightLink.href = 'https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.7.0/styles/github-dark.min.css';
    } else {
        html.classList.remove('dark');
        themeToggle.innerHTML = '<i class="fas fa-sun text-2xl"></i>';
        highlightLink.href = 'https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.7.0/styles/github.min.css';
    }
    localStorage.setItem('theme', theme);

    if (originalCode) {
        codeBlock.textContent = originalCode;
        hljs.highlightElement(codeBlock);
        //hljs.lineNumbersBlock(codeBlock);
    }
}

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

const highlightToggle = document.getElementById('highlightToggle');
const codeBlock = document.getElementById('codeBlock');
const codeZone = document.getElementById('codeZone');

highlightToggle.addEventListener('change', () => {
    if (highlightToggle.checked) {
        codeBlock.textContent = originalCode;
        codeZone.classList.remove('break-words')
        codeZone.classList.remove('text-wrap')
        hljs.highlightElement(codeBlock);
        //hljs.lineNumbersBlock(codeBlock);
    } else {
        codeBlock.textContent = originalCode;
        codeZone.classList.add('break-words')
        codeZone.classList.add('text-wrap')
    }
});

const copyBtn = document.getElementById('copyBtn');
copyBtn.addEventListener('click', () => {
    navigator.clipboard.writeText(codeBlock.textContent).then(() => {
        alert('Code copied to clipboard!');
    });
});

document.getElementById('rawBtn').addEventListener('click', () => {
    alert('Raw view functionality to be implemented');
});

document.getElementById('duplicateBtn').addEventListener('click', () => {
    alert('Duplicate post functionality to be implemented');
});

document.getElementById('editBtn').addEventListener('click', () => {
    alert('Edit post functionality to be implemented');
});

document.getElementById('deleteBtn').addEventListener('click', () => {
    alert('Delete post functionality to be implemented');
});

document.getElementById('loginBtn').addEventListener('click', () => {
    alert('Login functionality to be implemented');
});