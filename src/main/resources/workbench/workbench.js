'use strict';

// The session token arrives in the URL fragment, which the browser never sends
// to a server. Keep it for this tab only and remove it from the address bar.
const TOKEN_KEY = 'catalog-workbench-token';
function takeTokenFromFragment() {
  const fragment = new URLSearchParams(location.hash.slice(1));
  if (!fragment.get('token')) return false;
  sessionStorage.setItem(TOKEN_KEY, fragment.get('token'));
  history.replaceState(null, '', location.pathname);
  return true;
}
takeTokenFromFragment();
// Opening the printed URL in a tab that already shows the page only changes the fragment.
window.addEventListener('hashchange', () => { if (takeTokenFromFragment()) location.reload(); });
const token = sessionStorage.getItem(TOKEN_KEY);

const $ = (id) => document.getElementById(id);
let status = null;

function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = String(text);
  if (className) node.className = className;
  return node;
}

async function api(path, body) {
  const options = { headers: { 'X-Workbench-Token': token || '' } };
  if (body !== undefined) {
    options.method = 'POST';
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.message || data.error || `HTTP ${response.status}`);
    error.code = data.error;
    throw error;
  }
  return data;
}

function showResult(...nodes) {
  const result = $('result');
  result.replaceChildren(...nodes);
}

function showError(error) {
  const box = el('div');
  box.append(el('p', `실패: ${error.code || '오류'}`, 'bad'), el('pre', error.message));
  showResult(box);
}

function actor() {
  const value = $('actor').value.trim();
  if (!value) throw Object.assign(new Error('운영자 이름을 입력하세요.'), { code: 'ACTOR_REQUIRED' });
  return value;
}

function manifest() {
  try {
    const value = JSON.parse($('manifest').value);
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error();
    return value;
  } catch {
    throw Object.assign(new Error('manifest JSON을 읽을 수 없습니다.'), { code: 'MANIFEST_INVALID' });
  }
}

function baselineRequest() {
  const choice = $('baseline').value;
  if (choice === 'none') return 'none';
  if (choice === 'published') return status && status.published ? status.published.id : 'none';
  return undefined;
}

function setManifest(value) {
  $('manifest').value = JSON.stringify(value, null, 2);
  renderMapAssets();
}

function renderMapAssets() {
  const body = $('map-assets');
  body.replaceChildren();
  let value;
  try { value = JSON.parse($('manifest').value); } catch { return; }
  (value.mapAssets || []).forEach((asset, index) => {
    const input = el('input');
    input.type = 'text';
    input.value = asset.imageUrl || '';
    input.setAttribute('aria-label', `${asset.mapId} ${asset.version} 이미지 URI`);
    input.addEventListener('change', () => {
      const current = manifest();
      current.mapAssets[index].imageUrl = input.value.trim();
      $('manifest').value = JSON.stringify(current, null, 2);
    });
    const url = el('td');
    url.append(input);
    const row = el('tr');
    row.append(el('td', asset.mapId, 'id'), el('td', asset.version, 'id'), url);
    body.append(row);
  });
}

function renderStatus() {
  const list = $('status');
  list.replaceChildren();
  const add = (term, value, className) => list.append(el('dt', term), el('dd', value, className));
  add('축제 ID', status.festivalId);
  add('현재 게시본', status.published ? `#${status.published.revisionNumber} (${status.published.id})` : '없음');
  add('권한', status.publishEnabled ? 'export + publish' : 'export 전용 (가져오기·게시 불가)',
    status.publishEnabled ? 'ok' : 'bad');
  add('백엔드 확인', status.backendCheckConfigured ? '설정됨' : '미설정');
  $('import').disabled = !status.publishEnabled;
  $('post-publish-check').disabled = !status.backendCheckConfigured;

  const body = $('revisions');
  body.replaceChildren();
  status.revisions.forEach((revision) => {
    const actions = el('td');
    const exportButton = el('button', '내보내기');
    exportButton.type = 'button';
    exportButton.addEventListener('click', () => exportRevision(revision.id));
    actions.append(exportButton);
    if (revision.state === 'draft' && status.publishEnabled) {
      const publishButton = el('button', '게시', 'write');
      publishButton.type = 'button';
      publishButton.addEventListener('click', () => publishRevision(revision));
      actions.append(' ', publishButton);
    }
    const row = el('tr');
    row.append(
      el('td', `#${revision.revisionNumber}`),
      el('td', revision.state),
      el('td', revision.id, 'id'),
      el('td', revision.baseRevisionId || '-', 'id'),
      actions,
    );
    body.append(row);
  });
}

async function refresh() {
  try {
    status = await api('/api/status');
    renderStatus();
  } catch (error) {
    showError(error);
  }
}

async function exportRevision(revisionId) {
  try {
    const data = await api('/api/export', { revisionId });
    setManifest(data.manifest);
    const box = el('div');
    box.append(el('p', `revision ${revisionId}을(를) 불러왔습니다.`, 'ok'));
    if (data.findings.length) {
      box.append(el('p', '확인이 필요한 항목', 'bad'));
      const list = el('ul');
      data.findings.forEach((finding) => list.append(el('li', finding)));
      box.append(list);
    }
    showResult(box);
  } catch (error) {
    showError(error);
  }
}

async function validate() {
  try {
    const report = await api('/api/validate', { manifest: manifest(), baselineRevisionId: baselineRequest() });
    const box = el('div');
    box.append(el('p', report.valid ? '검증 통과' : '검증 실패', report.valid ? 'ok' : 'bad'));
    if (report.error) box.append(el('pre', report.error));
    const baseline = report.baseline;
    box.append(el('p', baseline.matches
      ? '기준 revision이 현재 게시본과 같습니다.'
      : `기준 revision(${baseline.statedBaselineRevisionId || '없음'})이 현재 게시본(${baseline.currentPublishedRevisionId || '없음'})과 다릅니다. 가져오기가 거절됩니다.`,
    baseline.matches ? 'ok' : 'bad'));
    showResult(box);
  } catch (error) {
    showError(error);
  }
}

async function diff() {
  try {
    const result = await api('/api/diff', { manifest: manifest() });
    const box = el('div');
    box.append(el('p', `비교 대상: ${result.againstRevisionId || '게시본 없음(빈 catalog)'}`));
    if (!result.sections.length) box.append(el('p', '차이가 없습니다.', 'ok'));
    result.sections.forEach((section) => {
      box.append(el('h3', section.name));
      const list = el('ul');
      [['추가', section.added], ['삭제', section.removed], ['변경', section.changed]].forEach(([label, keys]) => {
        if (keys.length) list.append(el('li', `${label} ${keys.length}: ${keys.join(', ')}`));
      });
      box.append(list);
    });
    showResult(box);
  } catch (error) {
    showError(error);
  }
}

async function importManifest() {
  try {
    const body = { manifest: manifest(), actor: actor(), baselineRevisionId: baselineRequest() };
    const result = await api('/api/import', body);
    showResult(el('p', `초안 revision ${result.revisionId}을(를) 만들었습니다. 목록에서 게시할 수 있습니다.`, 'ok'));
    await refresh();
  } catch (error) {
    showError(error);
  }
}

async function publishRevision(revision) {
  try {
    const name = actor();
    if (!confirm(`revision #${revision.revisionNumber}을(를) 게시할까요? 공개 서비스는 백엔드 재시작 뒤 이 revision을 제공합니다.`)) return;
    const result = await api('/api/publish', { revisionId: revision.id, actor: name });
    const box = el('div');
    box.append(el('p', `revision #${result.published.revisionNumber}을(를) 게시했습니다.`, 'ok'));
    const steps = el('ol');
    result.nextSteps.forEach((step) => steps.append(el('li', step)));
    box.append(steps);
    showResult(box);
    await refresh();
  } catch (error) {
    showError(error);
  }
}

async function postPublishCheck() {
  try {
    const result = await api('/api/post-publish-check', {});
    const box = el('div');
    box.append(
      el('p', `/readyz: ${result.readyzStatus}`, result.ready ? 'ok' : 'bad'),
      el('p', `공개 API revision ${result.servedRevision ?? '-'} / 게시 revision ${result.publishedRevision ?? '-'}`,
        result.revisionMatches ? 'ok' : 'bad'),
    );
    showResult(box);
  } catch (error) {
    showError(error);
  }
}

function download() {
  try {
    const blob = new Blob([JSON.stringify(manifest(), null, 2)], { type: 'application/json' });
    const link = el('a');
    link.href = URL.createObjectURL(blob);
    link.download = 'catalog-manifest.json';
    link.click();
    URL.revokeObjectURL(link.href);
  } catch (error) {
    showError(error);
  }
}

$('manifest-file').addEventListener('change', async (event) => {
  const [file] = event.target.files;
  if (!file) return;
  try {
    setManifest(JSON.parse(await file.text()));
  } catch {
    showError(Object.assign(new Error('JSON 파일을 읽을 수 없습니다.'), { code: 'MANIFEST_INVALID' }));
  }
});
$('manifest').addEventListener('change', renderMapAssets);
$('refresh').addEventListener('click', refresh);
$('validate').addEventListener('click', validate);
$('diff').addEventListener('click', diff);
$('import').addEventListener('click', importManifest);
$('post-publish-check').addEventListener('click', postPublishCheck);
$('download').addEventListener('click', download);

if (!token) {
  showError(Object.assign(new Error('터미널에 출력된 워크벤치 주소(#token=...)로 다시 여세요.'), { code: 'SESSION_TOKEN_REQUIRED' }));
} else {
  refresh();
}
