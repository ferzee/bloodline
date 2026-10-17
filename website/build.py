#!/usr/bin/env python3
"""
Builds the Bloodline Chronicles website into website/dist/:  python3 website/build.py

The pages are plain HTML and CSS, so dist/ can be hosted anywhere (GitHub Pages, a web server, ...).
Page content lives in website/content/: home.html, servers.html and one file per guide chapter in
content/guide/. This script wraps each one in the shared header, footer and bookmark tabs, and writes
the search index the guide's search box uses.

A guide chapter starts with a comment holding its title, its bookmark label, the opening paragraph
and a description for search engines; the rest is the chapter's HTML. Each <h2 id="..."> becomes an
entry in the page's contents and in the search.
"""
import html
import json
import os
import re
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
CONTENT = os.path.join(HERE, "content")
DIST = os.path.join(HERE, "dist")

# The address the site will be published at, such as "https://bloodline.example.com/". Link previews on social
# media need it to find the preview image; while it is None, pages have no preview image.
SITE_URL = None

# Where the mod can be downloaded. Change these once the project pages exist; a link set to None is left out.
LINKS = {
	"modrinth": "https://modrinth.com/mod/bloodline-chronicles",
	"curseforge": "https://www.curseforge.com/minecraft/mc-mods/bloodline-chronicles",
	"github": None,
}

NUMERALS = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV"]

FLOURISH = (
	'<svg class="flourish" viewBox="0 0 280 24" width="280" height="24" aria-hidden="true">'
	'<path d="M6 12H96M184 12H274" stroke="currentColor" stroke-width="1.5"/>'
	'<path d="M96 12q9-7 18 0q-9 7-18 0M166 12q9-7 18 0q-9 7-18 0" fill="none" stroke="currentColor" stroke-width="1.5"/>'
	'<rect x="119" y="11" width="3" height="2" fill="currentColor"/><rect x="158" y="11" width="3" height="2" fill="currentColor"/>'
	'<path d="M139 3h2v2h2v2h2v2h2v2h2v2h-2v2h-2v2h-2v2h-2v2h-2v-2h-2v-2h-2v-2h-2v-2h-2v-2h2v-2h2v-2h2v-2h2z" fill="#A62620" shape-rendering="crispEdges"/>'
	'<rect x="138" y="10" width="4" height="4" fill="#B08434"/></svg>'
)

DOWNLOAD_ICON = (
	'<svg width="14" height="14" viewBox="0 0 7 7" shape-rendering="crispEdges" aria-hidden="true">'
	'<rect x="3" y="0" width="1" height="3" fill="currentColor"/><rect x="1" y="3" width="5" height="1" fill="currentColor"/>'
	'<rect x="2" y="4" width="3" height="1" fill="currentColor"/><rect x="3" y="5" width="1" height="1" fill="currentColor"/></svg>'
)

ARROW = (
	'<svg width="12" height="12" viewBox="0 0 7 7" shape-rendering="crispEdges" aria-hidden="true">'
	'<rect x="0" y="3" width="3" height="1" fill="currentColor"/><rect x="3" y="1" width="1" height="5" fill="currentColor"/>'
	'<rect x="4" y="2" width="1" height="3" fill="currentColor"/><rect x="5" y="3" width="1" height="1" fill="currentColor"/></svg>'
)

FONTS = ("https://fonts.googleapis.com/css2?family=Cinzel:wght@500;600;700&family=Courier+Prime:wght@400;700"
	"&family=EB+Garamond:ital,wght@0,400;0,500;0,600;1,400&family=IM+Fell+English:ital@0;1&family=IM+Fell+English+SC"
	"&family=UnifrakturMaguntia&display=swap")


def strip_tags(text):
	return html.unescape(re.sub(r"<[^>]+>", "", text))


class Chapter:
	def __init__(self, path, number):
		raw = open(path, encoding="utf-8").read()
		meta, self.body = re.match(r"<!--\n(.*?)\n-->\n(.*)", raw, re.S).groups()
		fields = dict(line.split(": ", 1) for line in meta.splitlines())
		self.title = fields["title"]
		self.tab = fields["tab"]
		self.lead = fields["lead"]
		self.description = fields["description"]
		self.number = number
		self.numeral = NUMERALS[number - 1]
		self.slug = re.sub(r"^\d+-", "", os.path.splitext(os.path.basename(path))[0])
		self.file = self.slug + ".html"
		self.sections = re.findall(r'<h2 id="([^"]+)">(.*?)</h2>', self.body)


def link(name):
	return LINKS.get(name)


def download_buttons(icon=True):
	parts = []
	if link("modrinth"):
		parts.append(f'<a class="btn btn-green" href="{link("modrinth")}">{DOWNLOAD_ICON if icon else ""}Download on Modrinth</a>')
	if link("curseforge"):
		parts.append(f'<a class="btn btn-leather" href="{link("curseforge")}">{DOWNLOAD_ICON if icon else ""}Download on CurseForge</a>')
	return "\n".join(parts)


def header(root, current):
	def nav(href, label, key):
		cur = ' aria-current="page"' if key == current else ""
		return f'<a href="{root}{href}"{cur}>{label}</a>'
	return f'''<a class="skip" href="#content">Skip to the content</a>
<header class="hdr">
<div class="wrap hdr-in">
<a class="logo" href="{root}index.html" aria-label="Bloodline Chronicles, home"><img class="logo-img" src="{root}assets/img/logo.png" alt="" width="46" height="46"><span class="logo-name">Bloodline Chronicles</span></a>
<button class="menu" type="button" aria-label="Open menu" aria-expanded="false"><svg width="22" height="18" viewBox="0 0 11 9" shape-rendering="crispEdges" aria-hidden="true"><rect x="0" y="0" width="11" height="2" fill="currentColor"/><rect x="0" y="3.5" width="11" height="2" fill="currentColor"/><rect x="0" y="7" width="11" height="2" fill="currentColor"/></svg></button>
<div class="hdr-r">
<nav class="nav" aria-label="Main">
{nav("index.html", "Home", "home")}
{nav("guide/index.html", "Guide", "guide")}
{nav("servers.html", "Server owners", "servers")}
</nav>
<a class="btn btn-green btn-sm" href="{root}index.html#download">Download</a>
</div>
</div>
</header>'''


def footer(root, chapters):
	downloads = "".join(f'<li><a href="{link(k)}">{label}</a></li>' for k, label in
		(("modrinth", "Modrinth"), ("curseforge", "CurseForge"), ("github", "GitHub")) if link(k))
	guide = "".join(f'<li><a href="{root}guide/{c.file}">{c.tab}</a></li>' for c in chapters[:4])
	return f'''<footer class="ftr">
<div class="wrap">
<div class="ftr-grid">
<div>
<a class="logo" href="{root}index.html" aria-label="Bloodline Chronicles, home"><img class="logo-img" src="{root}assets/img/logo.png" alt="" width="46" height="46"><span class="logo-name">Bloodline Chronicles</span></a>
<p class="ftr-tag">You don't play one hero. You play a family.</p>
</div>
<div>
<h2>Download</h2>
<ul>{downloads}</ul>
</div>
<div>
<h2>Guide</h2>
<ul>{guide}<li><a href="{root}guide/faq.html">FAQ</a></li></ul>
</div>
<div>
<h2>Servers</h2>
<ul><li><a href="{root}servers.html#install">Installing</a></li><li><a href="{root}servers.html#settings">The settings file</a></li><li><a href="{root}servers.html#commands">Commands</a></li></ul>
</div>
</div>
<div class="legal">
<p>Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.</p>
<p>A Fabric mod for Minecraft 26.3</p>
</div>
</div>
</footer>'''


def page(root, current, title, description, main, chapters, search=False):
	scripts = f'<script src="{root}assets/search-index.js" defer></script>\n' if search else ""
	full_title = "Bloodline Chronicles" if title is None else f"{title} · Bloodline Chronicles"
	social = ""
	if SITE_URL:
		image = SITE_URL.rstrip("/") + "/assets/img/social.png"
		social = (f'<meta property="og:image" content="{image}">\n<meta property="og:image:width" content="1200">\n'
			f'<meta property="og:image:height" content="630">\n<meta name="twitter:card" content="summary_large_image">\n')
	return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{full_title}</title>
<meta name="description" content="{html.escape(description)}">
<meta property="og:title" content="{full_title}">
<meta property="og:description" content="{html.escape(description)}">
<meta name="theme-color" content="#62261E">
{social}<link rel="icon" type="image/png" sizes="32x32" href="{root}assets/img/favicon-32.png">
<link rel="icon" type="image/png" sizes="16x16" href="{root}assets/img/favicon-16.png">
<link rel="apple-touch-icon" href="{root}assets/img/apple-touch-icon.png">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="{html.escape(FONTS)}">
<link rel="stylesheet" href="{root}assets/style.css">
{scripts}<script src="{root}assets/site.js" defer></script>
</head>
<body data-root="{root}">
{header(root, current)}
{main}
{footer(root, chapters)}
</body>
</html>
'''


def guide_top(heading="Everything The Chronicle knows"):
	return f'''<div class="wrap top">
<div class="top-row">
<div>
<p class="kicker">The Guide</p>
<p class="top-t">{heading}</p>
</div>
<div class="search" role="search">
<div class="search-box">
<label for="search">Search</label>
<input id="search" type="search" placeholder="Oathblade, braziers, heirs…" autocomplete="off" role="combobox" aria-controls="search-results" aria-expanded="false" aria-autocomplete="list">
</div>
<ul class="results" id="search-results" role="listbox" aria-label="Search results" hidden></ul>
</div>
</div>
</div>'''


def tabs(chapters, current):
	items = []
	for c in chapters:
		cur = ' aria-current="page"' if c is current else ""
		items.append(f'<a class="tab" href="{c.file}"{cur}><span class="num">{c.numeral}</span>{c.tab}</a>')
	return '<nav class="tabs" aria-label="Guide chapters">\n<p class="tabs-t">Contents</p>\n' + "\n".join(items) + "\n</nav>"


def fix_links(body):
	"""Content links to other chapters by their file name (08-crypts.html); the site drops the number."""
	return re.sub(r'href="\d+-([a-z0-9-]+\.html)', r'href="\1', body)


def chapter_page(chapters, i):
	c = chapters[i]
	toc = "".join(f'<li><a href="#{sid}">{strip_tags(t)}</a></li>' for sid, t in c.sections)
	turns = []
	if i > 0:
		p = chapters[i - 1]
		turns.append(f'<a class="turn prev" href="{p.file}"><span class="turn-k">← Turn back · {p.numeral}</span><span class="turn-t">{p.title}</span></a>')
	if i + 1 < len(chapters):
		n = chapters[i + 1]
		turns.append(f'<a class="turn next" href="{n.file}"><span class="turn-k">Turn the page · {n.numeral} →</span><span class="turn-t">{n.title}</span></a>')
	main = f'''{guide_top()}
<div class="book">
{tabs(chapters, c)}
<main class="gpage" id="content">
<div class="ribbon" aria-hidden="true"></div>
<p class="running">Chapter {c.numeral}</p>
<article class="prose">
<p class="crumbs"><a href="index.html">Guide</a><span aria-hidden="true">›</span><span>{c.title}</span></p>
<h1>{c.title}</h1>
<p class="lead">{c.lead}</p>
<ul class="toc" aria-label="On this page">{toc}</ul>
{FLOURISH}
{fix_links(c.body)}
</article>
<nav class="turns" aria-label="Previous and next chapter">
{chr(10).join(turns)}
</nav>
</main>
</div>'''
	return page("../", "guide", strip_tags(c.title), c.description, main, chapters, search=True)


def guide_index(chapters):
	cards = "".join(
		f'<li><a href="{c.file}"><span class="r-c">Chapter {c.numeral}</span><span class="r-t">{c.title}</span>'
		f'<span class="r-s">{html.escape(c.description)}</span></a></li>' for c in chapters)
	main = f'''{guide_top()}
<div class="book">
{tabs(chapters, None)}
<main class="gpage" id="content">
<div class="ribbon" aria-hidden="true"></div>
<p class="running">Contents</p>
<article class="prose">
<h1>The Guide</h1>
<p class="lead">Everything about playing Bloodline Chronicles, from your first character to the last act of the Founder's Oath. In the game itself, the Chronicle shows you what your house needs next.</p>
{FLOURISH}
<ol class="chapters">{cards}</ol>
</article>
</main>
</div>'''
	return page("../", "guide", "Guide", "The player guide for Bloodline Chronicles: heirs, heirlooms, renown, the Founder's Oath, inns, crypts and more.", main, chapters, search=True)


def search_index(chapters):
	entries = []
	for c in chapters:
		parts = re.split(r'<h2 id="([^"]+)">(.*?)</h2>', c.body)
		intro = strip_tags(c.lead + " " + parts[0])
		entries.append({"chapter": f"Chapter {c.numeral}", "title": strip_tags(c.title), "url": f"guide/{c.file}", "text": " ".join(intro.split())})
		for j in range(1, len(parts), 3):
			sid, title, text = parts[j], parts[j + 1], parts[j + 2]
			entries.append({"chapter": f"{c.numeral} · {strip_tags(c.title)}", "title": strip_tags(title),
				"url": f"guide/{c.file}#{sid}", "text": " ".join(strip_tags(text).split())})
	return "window.BLOODLINE_SEARCH = " + json.dumps(entries, ensure_ascii=False) + ";\n"


def fill(text, chapters):
	"""Placeholders in home.html and servers.html."""
	text = text.replace("{{FLOURISH}}", FLOURISH).replace("{{ARROW}}", ARROW)
	text = text.replace("{{DOWNLOADS}}", download_buttons()).replace("{{DOWNLOADS_PLAIN}}", download_buttons(icon=False))
	for c in chapters:
		text = text.replace("{{" + c.slug + "}}", "guide/" + c.file)
	return text


def main():
	guide_dir = os.path.join(CONTENT, "guide")
	files = sorted(f for f in os.listdir(guide_dir) if f.endswith(".html"))
	chapters = [Chapter(os.path.join(guide_dir, f), i + 1) for i, f in enumerate(files)]

	if os.path.isdir(DIST):
		shutil.rmtree(DIST)
	os.makedirs(os.path.join(DIST, "guide"))
	shutil.copytree(os.path.join(HERE, "assets"), os.path.join(DIST, "assets"))

	def write(path, text):
		with open(os.path.join(DIST, path), "w", encoding="utf-8") as f:
			f.write(text)

	home = fill(open(os.path.join(CONTENT, "home.html"), encoding="utf-8").read(), chapters)
	write("index.html", page("", "home", None,
		"Bloodline Chronicles is a Minecraft mod where you play a family: when your character dies, their heir carries on.", home, chapters))
	servers = fill(open(os.path.join(CONTENT, "servers.html"), encoding="utf-8").read(), chapters)
	write("servers.html", page("", "servers", "Server owners",
		"Installing Bloodline Chronicles on a server, its settings file and its commands.", servers, chapters))
	write("guide/index.html", guide_index(chapters))
	for i, c in enumerate(chapters):
		write("guide/" + c.file, chapter_page(chapters, i))
	write("assets/search-index.js", search_index(chapters))
	# GitHub Pages would otherwise run the files through Jekyll.
	write(".nojekyll", "")
	print(f"Built {len(chapters) + 3} pages in {os.path.relpath(DIST)}")


if __name__ == "__main__":
	main()
