// Bloodline Chronicles website: the mobile menu, the guide search and the copy buttons on code blocks.
(function () {
	'use strict';

	// --- Mobile menu ---
	var header = document.querySelector('.hdr');
	var menu = document.querySelector('.menu');
	if (header && menu) {
		menu.addEventListener('click', function () {
			var open = header.classList.toggle('open');
			menu.setAttribute('aria-expanded', open ? 'true' : 'false');
			menu.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
		});
	}

	// --- On narrow screens the bookmarks are a scrolling row: show the current one ---
	var current = document.querySelector('.tabs a[aria-current]');
	if (current) {
		var row = current.parentElement;
		if (row.scrollWidth > row.clientWidth) row.scrollLeft = current.offsetLeft - (row.clientWidth - current.offsetWidth) / 2;
	}

	// --- Copy buttons ---
	document.querySelectorAll('.copy').forEach(function (button) {
		button.addEventListener('click', function () {
			var pre = button.closest('.code').querySelector('pre');
			var text = pre.innerText.replace(/^\$ /gm, '');
			var done = function () {
				button.textContent = 'Copied';
				setTimeout(function () { button.textContent = 'Copy'; }, 1600);
			};
			if (navigator.clipboard) navigator.clipboard.writeText(text).then(done, function () {});
		});
	});

	// --- Guide search (the index is in search-index.js, written by build.py) ---
	var input = document.getElementById('search');
	var list = document.getElementById('search-results');
	var index = window.BLOODLINE_SEARCH || [];
	var root = document.body.getAttribute('data-root') || '';
	if (!input || !list || !index.length) return;

	function escape(text) {
		return text.replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; });
	}

	function highlight(text, words) {
		var html = escape(text);
		words.forEach(function (word) {
			if (word.length < 2) return;
			html = html.replace(new RegExp('(' + word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'ig'), '<mark>$1</mark>');
		});
		return html;
	}

	function snippet(text, words) {
		var lower = text.toLowerCase();
		var at = -1;
		words.forEach(function (word) { var i = lower.indexOf(word); if (i >= 0 && (at < 0 || i < at)) at = i; });
		if (at < 0) return text.slice(0, 120) + (text.length > 120 ? '…' : '');
		var start = Math.max(0, at - 40);
		var piece = text.slice(start, start + 130);
		return (start > 0 ? '…' : '') + piece + (start + 130 < text.length ? '…' : '');
	}

	function search(query) {
		var words = query.toLowerCase().split(/\s+/).filter(Boolean);
		if (!words.length) return [];
		var hits = [];
		index.forEach(function (entry) {
			var title = entry.title.toLowerCase();
			var text = entry.text.toLowerCase();
			var score = 0;
			for (var i = 0; i < words.length; i++) {
				var inTitle = title.indexOf(words[i]) >= 0;
				var inText = text.indexOf(words[i]) >= 0;
				if (!inTitle && !inText) return;
				score += inTitle ? 10 : 1;
			}
			hits.push({ entry: entry, score: score });
		});
		hits.sort(function (a, b) { return b.score - a.score; });
		return hits.slice(0, 8).map(function (hit) { return hit.entry; });
	}

	var active = -1;

	function render() {
		var query = input.value.trim();
		active = -1;
		if (!query) { list.hidden = true; input.setAttribute('aria-expanded', 'false'); return; }
		var words = query.toLowerCase().split(/\s+/).filter(Boolean);
		var hits = search(query);
		list.innerHTML = hits.length
			? hits.map(function (entry, i) {
				return '<li><a id="result-' + i + '" role="option" href="' + root + entry.url + '">'
					+ '<span class="r-c">' + escape(entry.chapter) + '</span>'
					+ '<span class="r-t">' + highlight(entry.title, words) + '</span>'
					+ '<span class="r-s">' + highlight(snippet(entry.text, words), words) + '</span></a></li>';
			}).join('')
			: '<li class="none">Nothing found in the guide.</li>';
		list.hidden = false;
		input.setAttribute('aria-expanded', 'true');
	}

	function move(step) {
		var links = list.querySelectorAll('a');
		if (!links.length) return;
		if (active >= 0) links[active].classList.remove('active');
		active = (active + step + links.length) % links.length;
		links[active].classList.add('active');
		links[active].scrollIntoView({ block: 'nearest' });
		input.setAttribute('aria-activedescendant', links[active].id);
	}

	input.addEventListener('input', render);
	input.addEventListener('focus', render);
	input.addEventListener('keydown', function (event) {
		if (event.key === 'ArrowDown') { event.preventDefault(); move(1); }
		else if (event.key === 'ArrowUp') { event.preventDefault(); move(-1); }
		else if (event.key === 'Enter') {
			var links = list.querySelectorAll('a');
			var target = links[active >= 0 ? active : 0];
			if (target) { event.preventDefault(); window.location.href = target.href; }
		} else if (event.key === 'Escape') { list.hidden = true; input.setAttribute('aria-expanded', 'false'); }
	});
	document.addEventListener('click', function (event) {
		if (!event.target.closest('.search')) list.hidden = true;
	});
})();
