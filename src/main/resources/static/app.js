(() => {
	const MAX_BYTES = 25 * 1024 * 1024;

	const $ = (id) => document.getElementById(id);
	const dropzone = $('dropzone');
	const fileInput = $('file-input');
	const status = $('status');
	const filesPanel = $('files');
	const fileList = $('file-list');
	const results = $('results');
	const filter = $('filter');
	const thead = document.querySelector('thead');
	const tbody = document.querySelector('tbody');
	const jsonView = $('json-view');

	let rows = [];
	let columns = [];
	let baseName = 'data';

	function showStatus(message, isError) {
		status.textContent = message;
		status.classList.toggle('error', !!isError);
		status.hidden = !message;
	}

	async function errorMessage(res) {
		if (res.status === 413) return 'File is too large (max 25 MB).';
		const body = await res.json().catch(() => null);
		return (body && (body.detail || body.message || body.error)) || res.statusText;
	}

	async function upload(file) {
		if (!file) return;
		if (file.size > MAX_BYTES) {
			showStatus(`"${file.name}" is ${(file.size / 1048576).toFixed(1)} MB, which is over the 25 MB limit.`, true);
			return;
		}

		showStatus(`Converting "${file.name}"…`);
		dropzone.classList.add('busy');
		const form = new FormData();
		form.append('file', file);

		try {
			const res = await fetch('conversions', { method: 'POST', body: form });
			if (!res.ok) throw new Error(await errorMessage(res));
			const conversion = await res.json();
			renderFiles(conversion);
			showStatus('');
			if (conversion.files.length) {
				await preview(conversion.files[0]);
			} else {
				results.hidden = true;
			}
		} catch (err) {
			filesPanel.hidden = true;
			results.hidden = true;
			showStatus(err.message || 'Upload failed.', true);
		} finally {
			dropzone.classList.remove('busy');
			fileInput.value = '';
		}
	}

	function renderFiles(conversion) {
		$('source-name').textContent = conversion.sourceFile;
		const n = conversion.files.length;
		$('file-count').textContent = `${n} sheet${n === 1 ? '' : 's'}`;
		$('download-zip').href = conversion.zipUrl;
		$('download-zip').hidden = n === 0;
		$('expiry').textContent = n
			? `Download links expire at ${new Date(conversion.expiresAt).toLocaleTimeString()}.`
			: 'The workbook has no sheets.';

		fileList.replaceChildren(...conversion.files.map((f) => {
			const li = document.createElement('li');

			const info = document.createElement('div');
			info.className = 'file-info';
			info.appendChild(cell('span', f.fileName, 'file-name'));
			if (f.hidden) info.appendChild(cell('span', 'hidden sheet', 'badge'));
			info.appendChild(cell('span', `${f.rows} row${f.rows === 1 ? '' : 's'} · ${formatSize(f.sizeBytes)}`, 'muted small'));

			const actions = document.createElement('div');
			actions.className = 'actions';
			const previewBtn = cell('button', 'Preview');
			previewBtn.type = 'button';
			previewBtn.addEventListener('click', () => preview(f).catch((err) => showStatus(err.message, true)));
			const link = cell('a', 'Download', 'button');
			link.href = f.url;
			link.download = f.fileName;
			actions.append(previewBtn, link);

			li.append(info, actions);
			li.dataset.url = f.url;
			return li;
		}));
		filesPanel.hidden = false;
	}

	async function preview(file) {
		const res = await fetch(file.url);
		if (!res.ok) throw new Error(await errorMessage(res));
		rows = await res.json();
		columns = rows.length ? Object.keys(rows[0]) : [];
		baseName = file.fileName.replace(/\.json$/, '');
		$('file-name').textContent = file.fileName;
		fileList.querySelectorAll('li').forEach((li) => li.classList.toggle('selected', li.dataset.url === file.url));
		filter.value = '';
		render();
		results.hidden = false;
	}

	function formatSize(bytes) {
		if (bytes < 1024) return `${bytes} B`;
		if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`;
		return `${(bytes / 1048576).toFixed(1)} MB`;
	}

	function filteredRows() {
		const q = filter.value.trim().toLowerCase();
		if (!q) return rows.map((row, i) => [i, row]);
		return rows
			.map((row, i) => [i, row])
			.filter(([, row]) => columns.some((c) => String(row[c]).toLowerCase().includes(q)));
	}

	function render() {
		const visible = filteredRows();
		$('row-count').textContent = visible.length === rows.length
			? `${rows.length} row${rows.length === 1 ? '' : 's'}`
			: `${visible.length} of ${rows.length} rows`;

		const headRow = document.createElement('tr');
		headRow.appendChild(cell('th', '#', 'row-num'));
		columns.forEach((c) => headRow.appendChild(cell('th', c)));
		thead.replaceChildren(headRow);

		const frag = document.createDocumentFragment();
		if (!visible.length) {
			const tr = document.createElement('tr');
			const td = cell('td', rows.length ? 'No rows match the filter.' : 'The sheet has no data rows.', 'no-match');
			td.colSpan = columns.length + 1;
			tr.appendChild(td);
			frag.appendChild(tr);
		}
		visible.forEach(([i, row]) => {
			const tr = document.createElement('tr');
			tr.appendChild(cell('td', i + 1, 'row-num'));
			columns.forEach((c) => {
				const v = row[c];
				tr.appendChild(v === '' ? cell('td', '—', 'empty-cell') : cell('td', v));
			});
			frag.appendChild(tr);
		});
		tbody.replaceChildren(frag);

		jsonView.textContent = JSON.stringify(visible.map(([, row]) => row), null, 2);
	}

	function cell(tag, text, className) {
		const el = document.createElement(tag);
		el.textContent = text;
		if (className) el.className = className;
		return el;
	}

	function toCsv(data) {
		const esc = (v) => /[",\r\n]/.test(v) ? `"${String(v).replace(/"/g, '""')}"` : String(v);
		return [columns.map(esc).join(','), ...data.map((row) => columns.map((c) => esc(row[c] ?? '')).join(','))].join('\r\n');
	}

	function download(content, type, ext) {
		const url = URL.createObjectURL(new Blob([content], { type }));
		const a = document.createElement('a');
		a.href = url;
		a.download = `${baseName}.${ext}`;
		a.click();
		URL.revokeObjectURL(url);
	}

	const visibleData = () => filteredRows().map(([, row]) => row);

	fileInput.addEventListener('change', () => upload(fileInput.files[0]));

	['dragenter', 'dragover'].forEach((evt) => dropzone.addEventListener(evt, (e) => {
		e.preventDefault();
		dropzone.classList.add('dragover');
	}));
	['dragleave', 'drop'].forEach((evt) => dropzone.addEventListener(evt, (e) => {
		e.preventDefault();
		dropzone.classList.remove('dragover');
	}));
	dropzone.addEventListener('drop', (e) => upload(e.dataTransfer.files[0]));

	filter.addEventListener('input', render);

	document.querySelectorAll('.tabs button').forEach((btn) => btn.addEventListener('click', () => {
		const view = btn.dataset.view;
		document.querySelectorAll('.tabs button').forEach((b) => b.setAttribute('aria-selected', String(b === btn)));
		$('table-view').hidden = view !== 'table';
		jsonView.hidden = view !== 'json';
	}));

	$('copy-json').addEventListener('click', async (e) => {
		const btn = e.currentTarget;
		try {
			await navigator.clipboard.writeText(JSON.stringify(visibleData(), null, 2));
			btn.textContent = 'Copied!';
		} catch {
			btn.textContent = 'Copy failed';
		}
		setTimeout(() => { btn.textContent = 'Copy JSON'; }, 1500);
	});
	$('download-csv').addEventListener('click', () =>
		download('﻿' + toCsv(visibleData()), 'text/csv;charset=utf-8', 'csv'));
})();
