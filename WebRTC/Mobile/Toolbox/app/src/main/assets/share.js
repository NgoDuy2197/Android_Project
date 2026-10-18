// Share board client. Text is saved to the host; files this browser attaches
// are kept here and streamed through the host only when someone downloads them.
(function () {
  var clientId = localStorage.getItem('dhClient');
  if (!clientId) { clientId = 'c' + Math.random().toString(36).slice(2) + Date.now().toString(36); localStorage.setItem('dhClient', clientId); }

  var heldFiles = {};      // fileId -> File (bytes this browser provides)
  var state = { items: [] };
  var openId = null;       // null = list view
  var saveTimer = null, lastSavedText = '';

  var app = document.getElementById('app');
  var titleEl = document.getElementById('title');
  var backBtn = document.getElementById('back');
  var addBtn = document.getElementById('add');

  function fmtSize(n) {
    if (!n) return '';
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
    return (n / 1073741824).toFixed(2) + ' GB';
  }
  function esc(s) { var d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }

  function api(path, opts) { return fetch(path, opts).then(function (r) { return r; }); }

  function refresh() {
    return api('/api/state').then(function (r) { return r.json(); }).then(function (j) {
      state = j; render();
    }).catch(function () {});
  }

  function render() {
    if (openId) {
      var it = state.items.filter(function (x) { return x.id === openId; })[0];
      if (!it) { openId = null; return render(); }
      renderItem(it);
    } else {
      renderList();
    }
  }

  function renderList() {
    backBtn.style.display = 'none';
    titleEl.textContent = 'Bảng chia sẻ';
    addBtn.style.display = '';
    if (!state.items.length) { app.innerHTML = '<div class="empty">Chưa có mục nào.<br>Bấm "+ Mục" để tạo.</div>'; return; }
    var h = '';
    state.items.forEach(function (it) {
      var preview = (it.text || '').slice(0, 80).replace(/\n/g, ' ');
      var nf = (it.files || []).length;
      h += '<div class="card list-item" data-id="' + it.id + '">'
        + '<h3>' + esc(it.title || 'Mục') + '</h3>'
        + '<div class="sub">' + (preview ? esc(preview) : '(trống)') + (nf ? ' · 📎 ' + nf + ' tệp' : '') + '</div></div>';
    });
    app.innerHTML = h;
    [].forEach.call(app.querySelectorAll('.list-item'), function (el) {
      el.onclick = function () { openId = el.getAttribute('data-id'); lastSavedText = ''; render(); };
    });
  }

  function renderItem(it) {
    backBtn.style.display = '';
    addBtn.style.display = 'none';
    titleEl.textContent = '';
    var filesHtml = (it.files || []).map(function (f) {
      var right = f.host || heldFiles[f.id]
        ? '<a href="/download/' + f.id + '">Tải</a>'
        : '<span class="sub">máy khác giữ</span>';
      var del = '<button data-del="' + f.id + '">Xoá</button>';
      return '<div class="file"><span class="nm">' + esc(f.name) + '</span><span class="sz">' + fmtSize(f.size) + '</span>' + right + del + '</div>';
    }).join('');

    app.innerHTML = '<div class="card">'
      + '<div class="row"><input class="ttl" id="ttl" value="' + esc(it.title) + '">'
      + '<button class="btn ghost" id="del">Xoá mục</button></div>'
      + '<textarea id="txt" placeholder="Nhập văn bản (siêu to cũng được)…">' + esc(it.text) + '</textarea>'
      + '<div class="row" style="margin-top:10px;gap:10px">'
      + '<button class="btn" id="attach">📎 Gắn tệp</button>'
      + '<span class="sub" id="savest"></span></div>'
      + '<div class="files">' + filesHtml + '</div>'
      + '<input type="file" id="picker" multiple style="display:none"></div>';

    var txt = document.getElementById('txt');
    lastSavedText = it.text || '';
    txt.oninput = function () {
      document.getElementById('savest').textContent = 'Đang lưu…';
      clearTimeout(saveTimer);
      saveTimer = setTimeout(saveText, 500);
    };
    document.getElementById('ttl').onchange = function (e) {
      api('/api/item/' + it.id + '/title', { method: 'POST', body: e.target.value });
    };
    document.getElementById('del').onclick = function () {
      if (!confirm('Xoá mục này?')) return;
      api('/api/item/' + it.id, { method: 'DELETE' }).then(function () { openId = null; refresh(); });
    };
    document.getElementById('attach').onclick = function () { document.getElementById('picker').click(); };
    document.getElementById('picker').onchange = function (e) { [].forEach.call(e.target.files, function (f) { attach(it.id, f); }); };
    [].forEach.call(app.querySelectorAll('[data-del]'), function (b) {
      b.onclick = function () {
        var fid = b.getAttribute('data-del');
        api('/api/file/' + fid, { method: 'DELETE' }).then(function () { delete heldFiles[fid]; refresh(); });
      };
    });
  }

  function saveText() {
    if (!openId) return;
    var txt = document.getElementById('txt'); if (!txt) return;
    if (txt.value === lastSavedText) { setSaved(); return; }
    var v = txt.value;
    api('/api/item/' + openId + '/text', { method: 'POST', body: v }).then(function () { lastSavedText = v; setSaved(); });
  }
  function setSaved() { var s = document.getElementById('savest'); if (s) s.textContent = 'Đã lưu ✓'; }

  function attach(itemId, file) {
    var meta = file.name + '\n' + file.size + '\n' + clientId;
    api('/api/item/' + itemId + '/attach', { method: 'POST', body: meta })
      .then(function (r) { return r.json(); })
      .then(function (j) { heldFiles[j.id] = file; refresh(); });
  }

  // Provider loop: wait for the host to ask us to upload a held file, then send it.
  function waitLoop() {
    api('/api/wait?client=' + clientId).then(function (r) {
      if (r.status === 200) return r.json(); return null;
    }).then(function (j) {
      if (j && j.fileId && heldFiles[j.fileId]) {
        fetch('/api/provide/' + j.fileId, { method: 'POST', body: heldFiles[j.fileId] }).catch(function () {});
      }
      setTimeout(waitLoop, 100);
    }).catch(function () { setTimeout(waitLoop, 2000); });
  }

  backBtn.onclick = function () { saveText(); openId = null; render(); };
  addBtn.onclick = function () { api('/api/item', { method: 'POST' }).then(refresh); };

  refresh();
  waitLoop();
  // Poll for changes, but don't clobber the textarea being edited.
  setInterval(function () { if (!openId || document.activeElement !== document.getElementById('txt')) refresh(); }, 2500);
})();
