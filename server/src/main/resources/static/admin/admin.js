'use strict';

const $ = (id) => document.getElementById(id);
const tabs = [...document.querySelectorAll('[data-tab]')];
const state = {project: '', tab: 'projects', session: null, search: null, report: null, eventLog: null, generation: 0,
    requests: {users: 0, events: 0, eventLog: 0, blacklist: 0}};
const number = new Intl.NumberFormat('zh-CN');
const time = new Intl.DateTimeFormat('zh-CN', {dateStyle: 'short', timeStyle: 'medium', timeZone: 'Asia/Shanghai'});

function showMessage(message, error = false) {
    $('message').textContent = message;
    $('message').classList.toggle('error', error);
    $('message').hidden = !message;
}

function task(action) {
    return async (event) => {
        event?.preventDefault();
        showMessage('');
        try { await action(event); }
        catch (error) { showMessage(error.message || '请求失败，请重试', true); }
    };
}

async function api(path, {method = 'GET', data} = {}) {
    const headers = {Accept: 'application/json'};
    if (method !== 'GET') headers[state.session.csrfHeader] = state.session.csrfToken;
    if (data !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(path, {method, headers, body: data === undefined ? undefined : JSON.stringify(data), cache: 'no-store'});
    if (response.redirected && new URL(response.url).pathname === '/login') {
        window.location.assign('/login');
        throw new Error('登录已过期');
    }
    if (!response.ok) {
        const info = await response.json().catch(() => ({}));
        throw new Error(info.detail || info.message || `请求失败 (${response.status})`);
    }
    return response.status === 204 ? null : response.json();
}

function projectUrl(project, endpoint, params = {}) {
    const query = new URLSearchParams(params).toString();
    return `/admin/api/projects/${encodeURIComponent(project)}/${endpoint}${query ? '?' + query : ''}`;
}

function button(label, action, css) {
    const element = document.createElement('button');
    element.type = 'button';
    element.textContent = label;
    if (css) element.className = css;
    element.addEventListener('click', task(action));
    return element;
}

function tag(label, alert = false) {
    const element = document.createElement('span');
    element.className = alert ? 'tag alert' : 'tag';
    element.textContent = label;
    return element;
}

function table(id, rows, columns, empty = '暂无记录') {
    const target = $(id);
    target.replaceChildren();
    if (!rows.length) {
        const cell = target.insertRow().insertCell();
        cell.colSpan = columns;
        cell.className = 'empty';
        cell.textContent = empty;
        return;
    }
    for (const row of rows) {
        const line = target.insertRow();
        for (const value of row) {
            const cell = line.insertCell();
            if (value instanceof Node) cell.append(value);
            else cell.textContent = value;
        }
    }
}

function pager(id, page, hasMore, go) {
    const prev = button('上一页', () => go(page - 1));
    const next = button('下一页', () => go(page + 1));
    prev.disabled = page <= 1;
    next.disabled = !hasMore;
    $(id).replaceChildren(prev, document.createTextNode(`第 ${page} 页`), next);
}

function projectKey(project) {
    const cell = document.createElement('div');
    cell.className = 'project-key';
    if (!project.apiKey) {
        const label = document.createElement('span');
        label.textContent = '旧密钥未保存';
        const hint = document.createElement('small');
        hint.className = 'muted';
        hint.textContent = '点击重置生成新密钥';
        cell.append(label, hint);
        return cell;
    }
    const key = document.createElement('code');
    key.className = 'key';
    key.tabIndex = 0;
    key.textContent = project.apiKey;
    key.setAttribute('aria-label', `${project.name} 的 API Key`);
    cell.append(key, button('复制密钥', () => copyProjectKey(key)));
    return cell;
}

async function copyProjectKey(key) {
    try {
        if (navigator.clipboard?.writeText) {
            await navigator.clipboard.writeText(key.textContent);
            showMessage('API Key 已复制');
            return;
        }
    } catch (_) { /* Fall back to selecting the visible key when clipboard access is denied. */ }
    key.focus();
    const selection = window.getSelection();
    if (selection) {
        const range = document.createRange();
        range.selectNodeContents(key);
        selection.removeAllRanges();
        selection.addRange(range);
        showMessage('浏览器未允许自动复制，已选中密钥，请按 Ctrl+C 复制');
    } else {
        showMessage('浏览器未允许自动复制，请手动选中表格中的 API Key 复制');
    }
}

function projectActions(project) {
    const actions = document.createElement('div');
    actions.className = 'project-actions';
    actions.append(button('查看行为', () => {
        state.project = project.id;
        $('project-select').value = project.id;
        return openTab('analysis');
    }), button('重置密钥', async (event) => {
        if (!window.confirm(`项目：${project.name}\n重置后旧密钥立即失效，需要更新业务端配置。确定重置？`)) return;
        const trigger = event.currentTarget;
        trigger.disabled = true;
        try {
            await api(projectUrl(project.id, 'reset-key'), {method: 'POST'});
            await loadProjects();
            showMessage('密钥已重置，请复制表格中的新密钥并更新业务端配置');
        } finally {
            trigger.disabled = false;
        }
    }, 'danger'));
    return actions;
}

async function loadProjects(selectId = state.project) {
    const projects = await api('/admin/api/projects');
    state.project = projects.some(project => project.id === selectId) ? selectId : (projects[0]?.id ?? '');
    const options = projects.map(project => new Option(project.name, project.id));
    $('project-select').replaceChildren(...(options.length ? options : [new Option('请先新建项目', '')]));
    $('project-select').value = state.project;
    $('project-select').disabled = !projects.length;
    table('project-rows', projects.map(project => [project.name, project.id, projectKey(project), time.format(project.createdAt),
        projectActions(project)]), 5);
}

async function openTab(tab) {
    state.tab = tab;
    state.generation++;
    state.report = null;
    state.eventLog = null;
    $('report').hidden = true;
    for (const item of tabs) {
        const selected = item.dataset.tab === tab;
        $(item.dataset.tab).hidden = !selected;
        if (selected) item.setAttribute('aria-current', 'page');
        else item.removeAttribute('aria-current');
    }
    if (tab === 'analysis') await startSearch();
    if (tab === 'event-log') await startEventLog();
    if (tab === 'blacklist') await loadBlacklist(1);
}

function localDate(value) {
    const date = new Date(value);
    return new Date(value - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 19);
}

function setRange(hours) {
    const now = Date.now();
    $('from').value = localDate(now - hours * 3_600_000);
    $('to').value = localDate(now);
}

function range() {
    const from = Date.parse($('from').value);
    const to = Date.parse($('to').value);
    if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to) throw new Error('请选择有效的时间范围');
    if (to - from > 24 * 3_600_000) throw new Error('一次最多查询 24 小时，请缩小时间范围');
    if (to < Date.now() - 24 * 3_600_000) throw new Error('仅保留最近 24 小时的数据，请调整时间范围');
    return {from, to};
}

async function startSearch() {
    state.report = null;
    $('report').hidden = true;
    if (!state.project) {
        table('analysis-rows', [], 7, '请先在项目管理中创建项目');
        $('analysis-pager').replaceChildren();
        return;
    }
    const userId = $('filter-user').value.trim();
    const minCount = Number($('min-count').value || '0');
    if (!Number.isSafeInteger(minCount) || minCount < 0) throw new Error('请求次数应为非负整数');
    const search = {...range(), minCount};
    if (userId) search.userId = userId;
    state.search = search;
    table('analysis-rows', [], 7, '正在查询…');
    $('analysis-pager').replaceChildren();
    await loadUsers(1);
}

async function loadUsers(page) {
    const search = state.search;
    const project = state.project;
    const generation = state.generation;
    const request = ++state.requests.users;
    const data = await api(projectUrl(project, 'v2/users', {...search, page}));
    if (generation !== state.generation || search !== state.search || request !== state.requests.users) return;
    table('analysis-rows', data.items.map(user => [user.userId, number.format(user.count),
        number.format(user.actionCount), number.format(user.failures),
        tag(user.suspected ? '疑似恶意' : '无命中', user.suspected),
        tag(user.blacklisted ? '已拉黑' : '未拉黑', user.blacklisted),
        button('查看报告', () => openReport(user.userId, search))]), 7, '没有符合条件的用户');
    pager('analysis-pager', data.page, data.hasMore, loadUsers);
}

function percent(value) { return value == null ? '—' : `${(100 * value).toFixed(1)}%`; }

function actionLabel(value) { return value === '__OTHER__' ? '其他行为（合并）' : value; }

function renderReport(data, context) {
    const actions = data.actions ?? [];
    const trend = data.trend ?? [];
    const evidence = data.evidence ?? [];
    $('report-title').textContent = `用户综合分析 · ${context.userId}`;
    const risk = evidence.length ? '疑似恶意' : '无命中';
    $('report-status').textContent = `${risk} · ${data.blacklisted ? '已拉黑' : '未拉黑'} · ${time.format(data.from)} 至 ${time.format(data.to)}（北京时间）`;
    $('report-coverage').textContent = data.coverage === 'SELECTED_ENDPOINTS'
        ? '分析范围：项目已接入的接口。以下统计覆盖这些接口的上报请求，未接入的接口不在分析范围内。'
        : '分析范围以项目已接入的接口为准，未接入的接口不在分析范围内。';
    const historyOnlyCount = data.historyOnlyCount ?? 0;
    const sampleCount = data.sampleCount ?? 0;
    const notes = [`历史补报 ${number.format(historyOnlyCount)} 次（不参与当时在线判断）`, `累计抽样 ${number.format(sampleCount)} 条（明细仅保留 12 小时）`];
    if (data.count > 0 || historyOnlyCount > 0 || sampleCount > 0) {
        if (data.samplesLimited) notes.push('明细达到采样或容量限制，仅展示已保留片段');
        if (data.actionCountsTruncated) notes.push('部分行为分类合并为“其他行为（合并）”');
        if (data.resourceCountsTruncated) notes.push('资源统计仅保留有限样本');
    }
    $('report-data-notes').textContent = notes.join(' · ');
    const metrics = [
        ['请求总量', number.format(data.count)], ['已记录分类', number.format(actions.length)],
        ['异常响应', number.format(data.failures)], ['异常响应占比', data.count ? percent(data.failures / data.count) : '—'],
        ['拒绝响应', number.format(data.denied)], ['风险证据', number.format(evidence.length)]
    ];
    $('report-metrics').replaceChildren(...metrics.map(([label, value]) => {
        const block = document.createElement('div');
        const term = document.createElement('dt');
        const info = document.createElement('dd');
        term.textContent = label;
        info.textContent = value;
        block.append(term, info);
        return block;
    }));
    table('action-rows', actions.map(item => [actionLabel(item.action), number.format(item.count),
        data.count ? percent(item.count / data.count) : '—']), 3, '该时段没有接口行为统计');
    const maximum = Math.max(1, ...trend.map(item => item.count));
    table('trend-rows', trend.map(item => {
        const bar = document.createElement('meter');
        bar.min = 0;
        bar.max = maximum;
        bar.value = item.count;
        bar.setAttribute('aria-label', `${time.format(item.at)} 请求 ${number.format(item.count)} 次`);
        return [time.format(item.at), number.format(item.count), number.format(item.failures), bar];
    }), 4, '该时段没有趋势数据');
    table('evidence-rows', evidence.map(item => [time.format(item.at), item.ruleId, item.reason]), 3, '该时段无规则命中');
    $('report-block').hidden = data.blacklisted;
    $('report').hidden = false;
}

async function openReport(userId, {from, to}) {
    if (!state.project || !userId) throw new Error('请选择项目并输入用户 ID');
    const context = {project: state.project, userId, from, to};
    state.report = context;
    $('report').hidden = true;
    table('event-rows', [], 8, '正在读取抽样片段…');
    $('event-pager').replaceChildren();
    const data = await api(projectUrl(context.project, 'v2/report', {userId, from, to}));
    if (state.report !== context) return;
    renderReport(data, context);
    await loadEvents(1);
    if (state.report === context) $('report').scrollIntoView({block: 'start'});
}

async function loadEvents(page) {
    const context = state.report;
    if (!context) return;
    const {project, userId, from, to} = context;
    const request = ++state.requests.events;
    const data = await api(projectUrl(project, 'v2/events', {userId, from, to, page}));
    if (state.report !== context || request !== state.requests.events) return;
    const kinds = {NORMAL: '正常抽样', ANOMALY: '异常片段'};
    table('event-rows', data.items.map(event => [time.format(event.occurredAt), actionLabel(event.action),
        event.route || '—',
        [event.resourceType, event.resourceId].filter(Boolean).join(' · ') || '—',
        event.ip || '—',
        tag(event.method === 'EVENT' ? '—' : event.status ?? '—', event.method !== 'EVENT' && Number(event.status) >= 400),
        kinds[event.sampleKind] ?? event.sampleKind ?? '抽样', event.eventId]), 8, '该时段没有保留的抽样请求或异常片段');
    pager('event-pager', data.page, data.hasMore, loadEvents);
}

function setEventLogLoading(loading) {
    $('event-log').setAttribute('aria-busy', String(loading));
    $('event-log-filters').querySelectorAll('button').forEach(item => { item.disabled = loading; });
    if (loading) $('event-log-pager').querySelectorAll('button').forEach(item => { item.disabled = true; });
}

function renderEventLogPager(context) {
    if (context.page) pager('event-log-pager', context.page, context.hasMore, loadEventLog);
    else $('event-log-pager').replaceChildren();
}

async function startEventLog() {
    state.eventLog = null;
    $('event-log-range').textContent = '';
    $('event-log-pager').replaceChildren();
    if (!state.project) {
        table('event-log-rows', [], 7, '请先在项目管理中创建项目');
        setEventLogLoading(false);
        return;
    }
    const pageSize = Number($('event-log-page-size').value);
    if (![50, 100, 200].includes(pageSize)) throw new Error('请选择有效的每页条数');
    state.eventLog = {project: state.project, userId: $('event-log-user').value.trim(), pageSize,
        from: null, to: null, cursors: [null], page: 0, hasMore: false};
    table('event-log-rows', [], 7, '正在读取事件…');
    await loadEventLog(1);
}

async function loadEventLog(page) {
    const context = state.eventLog;
    if (!context || !Number.isInteger(page) || page < 1 || page > context.cursors.length) return;
    const generation = state.generation;
    const request = ++state.requests.eventLog;
    const current = () => generation === state.generation && state.eventLog === context && request === state.requests.eventLog;
    const params = {pageSize: context.pageSize};
    if (context.userId) params.userId = context.userId;
    if (context.from !== null) { params.from = context.from; params.to = context.to; }
    if (context.cursors[page - 1] !== null) params.cursor = context.cursors[page - 1];
    setEventLogLoading(true);
    try {
        const data = await api(projectUrl(context.project, 'v2/event-log', params));
        if (!current()) return;
        context.from = data.from;
        context.to = data.to;
        const expired = data.from >= data.to;
        context.page = expired ? 1 : page;
        context.hasMore = !expired && data.hasMore && Boolean(data.nextCursor);
        context.cursors.length = context.page;
        if (context.hasMore) context.cursors.push(data.nextCursor);
        $('event-log-range').textContent = expired ? '查询范围已过期，请刷新最近事件'
            : `${time.format(data.from)} 至 ${time.format(data.to)}（北京时间） · 本页 ${number.format(data.items.length)} 条 · 刷新后查看最新事件`;
        table('event-log-rows', data.items.map(event => [time.format(event.occurredAt), event.userId,
            event.resourceId || '—', event.ip || '—', event.route || '—', actionLabel(event.action), event.eventId]), 7,
        expired ? '查询范围已过期，请刷新最近事件' : '最近 12 小时内没有符合条件的已接收事件');
    } catch (error) {
        if (!current()) return;
        if (!context.page) table('event-log-rows', [], 7, '事件加载失败，请重试');
        throw error;
    } finally {
        if (current()) {
            renderEventLogPager(context);
            setEventLogLoading(false);
        }
    }
}

async function loadBlacklist(page) {
    const project = state.project;
    const generation = state.generation;
    const request = ++state.requests.blacklist;
    if (!project) {
        table('blacklist-rows', [], 4, '请先在项目管理中创建项目');
        $('blacklist-pager').replaceChildren();
        return;
    }
    const data = await api(projectUrl(project, 'blacklist', {page}));
    if (generation !== state.generation || request !== state.requests.blacklist) return;
    if (page > 1 && !data.items.length) { await loadBlacklist(page - 1); return; }
    table('blacklist-rows', data.items.map(entry => [entry.userId, entry.reason, time.format(entry.addedAt),
        button('解除', async () => {
            if (!window.confirm(`解除用户 ${entry.userId} 的黑名单？`)) return;
            await api(projectUrl(project, 'blacklist', {userId: entry.userId}), {method: 'DELETE'});
            if (generation === state.generation) await loadBlacklist(page);
        }, 'danger')]), 4, '当前项目没有黑名单用户');
    pager('blacklist-pager', page, page * data.pageSize < data.total, loadBlacklist);
}

for (const tab of tabs) tab.addEventListener('click', task(() => openTab(tab.dataset.tab)));
$('project-select').addEventListener('change', task(() => {
    state.project = $('project-select').value;
    return openTab(state.tab);
}));
$('create-project').addEventListener('submit', task(async (event) => {
    const form = event.currentTarget;
    const data = await api('/admin/api/projects', {method: 'POST', data: Object.fromEntries(new FormData(form))});
    form.reset();
    await loadProjects(data.project.id);
    showMessage('项目已创建，可在表格中复制 API Key');
}));
$('analysis-filters').addEventListener('submit', task(startSearch));
$('event-log-filters').addEventListener('submit', task(startEventLog));
$('event-log-refresh').addEventListener('click', task(startEventLog));
document.querySelectorAll('[data-hours]').forEach(item => item.addEventListener('click', task(() => {
    setRange(Number(item.dataset.hours));
    return startSearch();
})));
$('direct-report').addEventListener('click', task(() => openReport($('filter-user').value.trim(), range())));
$('close-report').addEventListener('click', () => { state.report = null; $('report').hidden = true; });
$('report-block').addEventListener('click', task(async () => {
    $('blacklist-user').value = state.report.userId;
    await openTab('blacklist');
    $('add-blacklist').elements.reason.focus();
}));
$('add-blacklist').addEventListener('submit', task(async (event) => {
    if (!state.project) throw new Error('请先创建项目');
    const project = state.project;
    const generation = state.generation;
    const form = event.currentTarget;
    await api(projectUrl(project, 'blacklist'), {method: 'POST', data: Object.fromEntries(new FormData(form))});
    if (generation !== state.generation) return;
    form.reset();
    await loadBlacklist(1);
}));
$('logout').addEventListener('click', task(() => {
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = '/logout';
    const input = document.createElement('input');
    input.type = 'hidden';
    input.name = '_csrf';
    input.value = state.session.csrfToken;
    form.append(input);
    document.body.append(form);
    form.submit();
}));

setRange(24);
task(async () => {
    state.session = await api('/admin/api/session');
    await loadProjects();
})();
