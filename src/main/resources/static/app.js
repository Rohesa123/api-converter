const form = document.getElementById("convert-form");
const fileInput = document.getElementById("file");
const dropzone = document.getElementById("dropzone");
const fileListEl = document.getElementById("file-list");
const filesHead = document.getElementById("files-head");
const filesCount = document.getElementById("files-count");
const clearAllBtn = document.getElementById("clear-all");
const sourceSelect = document.getElementById("source");
const targetSelect = document.getElementById("target");
const statusEl = document.getElementById("status");
const submitBtn = document.getElementById("submit-btn");
const resultEl = document.getElementById("result");
const resultTitle = document.getElementById("result-title");
const resultSub = document.getElementById("result-sub");
const downloadBtn = document.getElementById("download-btn");

// Daftar file dipegang manual agar bisa tambah/hapus tanpa reset input.
let selectedFiles = [];

// Pasangan konversi yang didukung backend: { source:{id,label}, target:{id,label} }
let formatPairs = [];

// Hasil unduhan terakhir untuk tombol "Unduh ulang".
let lastBlob = null;
let lastFilename = "converted";

init();

async function init() {
    try {
        const res = await fetch("api/formats");
        formatPairs = await res.json();
    } catch (e) {
        formatPairs = [];
    }
    if (!formatPairs.length) {
        // fallback bila endpoint tidak tersedia
        formatPairs = [{
            source: { id: "insomnia-5.0", label: "Insomnia Collection v5" },
            target: { id: "openapi-3.0", label: "OpenAPI 3.0" }
        }];
    }
    populateSources();
    populateTargets();
}

function populateSources() {
    const seen = new Set();
    sourceSelect.innerHTML = "";
    formatPairs.forEach((f) => {
        if (seen.has(f.source.id)) return;
        seen.add(f.source.id);
        sourceSelect.appendChild(new Option(f.source.label, f.source.id));
    });
}

// Isi target sesuai sumber yang dipilih (mengikuti pasangan yang valid).
function populateTargets() {
    const src = sourceSelect.value;
    const seen = new Set();
    targetSelect.innerHTML = "";
    formatPairs
        .filter((f) => f.source.id === src)
        .forEach((f) => {
            if (seen.has(f.target.id)) return;
            seen.add(f.target.id);
            targetSelect.appendChild(new Option(f.target.label, f.target.id));
        });
}

sourceSelect.addEventListener("change", populateTargets);

// ---- pemilihan file (klik + drag/drop) ----

fileInput.addEventListener("change", () => {
    addFiles(fileInput.files);
    fileInput.value = ""; // reset agar file yang sama bisa dipilih lagi
});

["dragenter", "dragover"].forEach((ev) =>
    dropzone.addEventListener(ev, (e) => {
        e.preventDefault();
        dropzone.classList.add("dragover");
    })
);

["dragleave", "drop"].forEach((ev) =>
    dropzone.addEventListener(ev, (e) => {
        e.preventDefault();
        dropzone.classList.remove("dragover");
    })
);

dropzone.addEventListener("drop", (e) => {
    if (e.dataTransfer && e.dataTransfer.files) {
        addFiles(e.dataTransfer.files);
    }
});

function addFiles(fileList) {
    for (const f of fileList) {
        const dup = selectedFiles.some((x) => x.name === f.name && x.size === f.size);
        if (!dup) {
            selectedFiles.push(f);
        }
    }
    renderFiles();
}

function removeFile(index) {
    selectedFiles.splice(index, 1);
    renderFiles();
}

clearAllBtn.addEventListener("click", () => {
    selectedFiles = [];
    renderFiles();
});

function renderFiles() {
    fileListEl.innerHTML = "";
    filesHead.hidden = selectedFiles.length === 0;
    filesCount.textContent = selectedFiles.length
        ? `${selectedFiles.length} file dipilih`
        : "";
    selectedFiles.forEach((f, i) => {
        const li = document.createElement("li");
        li.className = "file-item";
        li.innerHTML = `
            <span class="fi-icon">📄</span>
            <span class="fi-name"></span>
            <span class="fi-size">${formatSize(f.size)}</span>
            <button type="button" class="fi-remove" title="Hapus">×</button>`;
        li.querySelector(".fi-name").textContent = f.name;
        li.querySelector(".fi-remove").addEventListener("click", () => removeFile(i));
        fileListEl.appendChild(li);
    });
    submitBtn.disabled = selectedFiles.length === 0;
}

function formatSize(bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
    return (bytes / (1024 * 1024)).toFixed(1) + " MB";
}

// ---- konversi ----

form.addEventListener("submit", async (e) => {
    e.preventDefault();
    hideStatus();
    resultEl.hidden = true;

    if (!selectedFiles.length) {
        showStatus("Pilih minimal satu file.", "error");
        return;
    }

    const output = form.querySelector('input[name="output"]:checked').value;
    const source = sourceSelect.value;
    const target = targetSelect.value;
    const multiple = selectedFiles.length > 1;

    const data = new FormData();
    data.append("source", source);
    data.append("target", target);
    data.append("output", output);
    if (multiple) {
        selectedFiles.forEach((f) => data.append("files", f));
    } else {
        data.append("file", selectedFiles[0]);
    }

    setLoading(true);
    try {
        const endpoint = multiple ? "api/convert/batch" : "api/convert";
        const res = await fetch(endpoint, { method: "POST", body: data });

        if (!res.ok) {
            const err = await res.json().catch(() => ({ message: "Konversi gagal." }));
            showStatus(err.message || "Konversi gagal.", "error");
            return;
        }

        const blob = await res.blob();
        const filename = parseFilename(
            res.headers.get("Content-Disposition"),
            multiple ? "converted-bundle.zip" : "converted.openapi." + output
        );

        lastBlob = blob;
        lastFilename = filename;
        triggerDownload(blob, filename);

        showResult(filename, multiple
            ? `${selectedFiles.length} file diproses menjadi satu arsip ZIP`
            : "Berhasil dikonversi");
        showStatus("Konversi selesai. File telah diunduh.", "success");
    } catch (err) {
        showStatus("Terjadi kesalahan: " + err.message, "error");
    } finally {
        setLoading(false);
    }
});

downloadBtn.addEventListener("click", () => {
    if (lastBlob) triggerDownload(lastBlob, lastFilename);
});

function triggerDownload(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
}

function parseFilename(disposition, fallback) {
    if (disposition) {
        const match = /filename="?([^"]+)"?/.exec(disposition);
        if (match) return match[1];
    }
    return fallback;
}

function setLoading(loading) {
    submitBtn.disabled = loading || selectedFiles.length === 0;
    submitBtn.classList.toggle("loading", loading);
    submitBtn.querySelector(".btn-label").textContent = loading ? "Memproses…" : "Konversi & Unduh";
}

function showResult(title, sub) {
    resultTitle.textContent = title;
    resultSub.textContent = sub;
    resultEl.hidden = false;
}

function showStatus(message, kind) {
    statusEl.textContent = message;
    statusEl.className = "status " + kind;
    statusEl.hidden = false;
}

function hideStatus() {
    statusEl.hidden = true;
}
