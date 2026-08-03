import './main.scss';

/**
 * Author-side preview behaviour only. The published experience for ContentBridge is the JSON API -
 * this bundle exists so editors get a readable rendering of each component inside the editor.
 */
function hydrateBanners() {
    document.querySelectorAll('.cb-banner').forEach((banner) => {
        const inner = banner.querySelector('.cb-banner__inner');
        if (inner && !inner.textContent.trim()) {
            banner.classList.add('cb-banner--image-only');
        }
    });
}

function init() {
    hydrateBanners();
}

if (document.readyState !== 'loading') {
    init();
} else {
    document.addEventListener('DOMContentLoaded', init);
}
