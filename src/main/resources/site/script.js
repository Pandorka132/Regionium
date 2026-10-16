(() => {
    'use strict';
    const canvas = document.querySelector('#canvas');
    const ctx = canvas.getContext('2d');
    document.querySelector('#host').textContent = location.host;
    const palette = ['#38bdf8', '#fb923c', '#c084fc', '#4ade80', '#f472b6', '#facc15', '#2dd4bf', '#a3e635', '#818cf8', '#fb7185'];
    const state = {
        dimensions: [],
        dimension: null,
        scale: 1,
        ox: 0,
        oy: 0,
        selected: null,
        drag: null,
        hasFit: false
    };
    const key = (x, z) => x + ',' + z;
    const regionColor = id => id == null ? '#64748b' : palette[Math.abs(Number(id)) % palette.length];
    const current = () => state.dimensions.find(d => d.id === state.dimension) || null;
    const chunks = () => current()?.chunks || [];
    const sections = () => current()?.sections || [];
    const players = () => current()?.players || [];
    const playerColor = player => palette[Math.abs(hashCode(player.uuid || player.name)) % palette.length];
    function hashCode(value) {
        let hash = 0;
        for (let i = 0; i < value.length; i++) hash = (hash * 31 + value.charCodeAt(i)) | 0;
        return hash;
    }
    const extent = () => {
        const list = chunks();
        if (!list.length) return null;
        return {
            minX: Math.min(...list.map(c => c.x)) - .5,
            maxX: Math.max(...list.map(c => c.x)) + .5,
            minZ: Math.min(...list.map(c => c.z)) - .5,
            maxZ: Math.max(...list.map(c => c.z)) + .5
        };
    };

    function resize() {
        const r = canvas.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
        canvas.width = Math.round(r.width * dpr);
        canvas.height = Math.round(r.height * dpr);
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        if (!state.hasFit) fit(); else draw();
    }

    function fit() {
        const e = extent(), w = canvas.clientWidth, h = canvas.clientHeight;
        if (!e) {
            draw();
            return
        }
        const spanX = Math.max(1, e.maxX - e.minX), spanZ = Math.max(1, e.maxZ - e.minZ);
        state.scale = Math.max(.05, Math.min(40, Math.min((w - 48) / spanX, (h - 48) / spanZ)));
        state.ox = (w - spanX * state.scale) / 2 - e.minX * state.scale;
        state.oy = (h - spanZ * state.scale) / 2 - e.minZ * state.scale;
        state.hasFit = true;
        draw();
    }

    function point(x, z) {
        return {x: x * state.scale + state.ox, y: z * state.scale + state.oy}
    }

    function draw() {
        const w = canvas.clientWidth, h = canvas.clientHeight;
        ctx.clearRect(0, 0, w, h);
        ctx.fillStyle = '#0b1018';
        ctx.fillRect(0, 0, w, h);
        const list = chunks(), secs = sections();
        document.querySelector('#message').hidden = list.length > 0;
        if (!list.length) {
            document.querySelector('#message').textContent = state.dimensions.length ? 'No chunks to display in this dimension.' : 'Waiting for Regionium data…';
            return
        }
        const cell = state.scale;
        for (const c of list) {
            const p = point(c.x - .5, c.z - .5);
            ctx.fillStyle = c.entityTicking ? '#24483e' : c.ticking ? '#30435d' : '#202b3b';
            ctx.fillRect(p.x + .5, p.y + .5, Math.max(1, cell - 1), Math.max(1, cell - 1));
        }
        if (cell > 4) {
            ctx.lineWidth = 1;
            ctx.strokeStyle = '#425066';
            ctx.globalAlpha = .45;
            ctx.beginPath();
            for (const c of list) {
                const p = point(c.x - .5, c.z - .5);
                ctx.rect(p.x, p.y, cell, cell)
            }
            ctx.stroke();
            ctx.globalAlpha = 1;
        }
        for (const s of secs) {
            const p = point(s.x * 8 - .5, s.z * 8 - .5), size = cell * 8;
            const ids = s.regionIds || [];
            ctx.strokeStyle = ids.length === 1 ? regionColor(ids[0]) : ids.length ? '#f8fafc' : '#64748b';
            ctx.lineWidth = 1.5;
            ctx.setLineDash(ids.length ? [] : [4, 4]);
            ctx.strokeRect(p.x + .5, p.y + .5, size - 1, size - 1);
            ctx.setLineDash([]);
            if (cell > 7) {
                ctx.fillStyle = '#e2e8f0';
                ctx.font = '11px system-ui';
                ctx.fillText(s.x + ',' + s.z, p.x + 4, p.y + 14)
            }
        }
        for (const player of players()) {
            const p = point(player.x / 16 - .5, player.z / 16 - .5);
            const radius = Math.max(4, Math.min(8, cell * .42));
            ctx.beginPath();
            ctx.arc(p.x, p.y, radius, 0, Math.PI * 2);
            ctx.fillStyle = playerColor(player);
            ctx.fill();
            ctx.lineWidth = 2;
            ctx.strokeStyle = '#f8fafc';
            ctx.stroke();
            if (cell >= 8) {
                ctx.font = '12px system-ui';
                ctx.lineWidth = 3;
                ctx.strokeStyle = '#0b1018';
                ctx.strokeText(player.name, p.x + radius + 3, p.y - radius - 2);
                ctx.fillStyle = '#f8fafc';
                ctx.fillText(player.name, p.x + radius + 3, p.y - radius - 2);
            }
        }
        if (state.selected?.type === 'player') {
            const player = state.selected.data, p = point(player.x / 16 + .5, player.z / 16 + .5);
            ctx.beginPath();
            ctx.arc(p.x, p.y, Math.max(8, state.scale * .55), 0, Math.PI * 2);
            ctx.strokeStyle = '#ffffff';
            ctx.lineWidth = 3;
            ctx.stroke();
        } else if (state.selected?.type === 'chunk') {
            const c = state.selected.data, p = point(c.x - .5, c.z - .5);
            ctx.strokeStyle = '#fff';
            ctx.lineWidth = 2.5;
            ctx.strokeRect(p.x + 1, p.y + 1, cell - 2, cell - 2);
        } else if (state.selected?.type === 'section') {
            const s = state.selected.data, p = point(s.x * 8 - .5, s.z * 8 - .5);
            ctx.strokeStyle = '#fff';
            ctx.lineWidth = 3;
            ctx.strokeRect(p.x + 1, p.y + 1, cell * 8 - 2, cell * 8 - 2);
        }
    }

    function showDetails(type, data) {
        state.selected = {type, data};
        document.querySelector('#details').textContent = JSON.stringify(data, null, 2);
        draw()
    }

    function render() {
        const list = chunks(), secs = sections();
        document.querySelector('#summary').innerHTML = '';
        const playerList = players();
        const stats = [['Chunks', list.length], ['Sections', secs.length], ['Regions', new Set(list.map(c => c.regionId).filter(id => id != null)).size], ['Players', playerList.length]];
        for (const [label, value] of stats) {
            const el = document.createElement('div');
            el.className = 'stat';
            el.innerHTML = '<span>' + label + '</span><strong>' + value + '</strong>';
            document.querySelector('#summary').append(el)
        }
        const regionIds = [...new Set(list.map(c => c.regionId).filter(id => id != null))].sort((a, b) => a - b);
        const legend = document.querySelector('#legend');
        legend.replaceChildren();
        for (const id of regionIds) {
            const row = document.createElement('div');
            row.className = 'legend-row';
            const sw = document.createElement('span');
            sw.className = 'swatch';
            sw.style.background = regionColor(id);
            const text = document.createElement('span');
            text.textContent = 'Region ' + id;
            row.append(sw, text);
            row.onclick = () => {
                const c = list.find(c => c.regionId === id);
                if (c) showDetails('chunk', c)
            };
            legend.append(row)
        }
        const playerContainer = document.querySelector('#players');
        playerContainer.replaceChildren();
        for (const player of playerList) {
            const row = document.createElement('div');
            row.className = 'legend-row';
            row.style.cursor = 'pointer';
            const sw = document.createElement('span');
            sw.className = 'swatch';
            sw.style.background = playerColor(player);
            const label = document.createElement('span');
            label.textContent = player.name;
            row.append(sw, label);
            row.onclick = () => showDetails('player', player);
            playerContainer.append(row);
        }
        if (state.selected) {
            document.querySelector('#details').textContent = JSON.stringify(state.selected.data, null, 2)
        }
        draw();
    }

    function chooseItem(x, z, forceSection = false) {
        const s = sections().find(s => Math.floor((x + .5) / 8) === s.x && Math.floor((z + .5) / 8) === s.z);
        if (forceSection && s) {
            showDetails('section', s);
            return
        }
        const c = chunks().find(c => c.x === x && c.z === z);
        if (c) {
            showDetails('chunk', c);
            return
        }
        if (s) showDetails('section', s);
    }

    function zoom(f, x = canvas.clientWidth / 2, y = canvas.clientHeight / 2) {
        const old = state.scale;
        state.scale = Math.max(.05, Math.min(80, old * f));
        state.ox = x - (x - state.ox) * state.scale / old;
        state.oy = y - (y - state.oy) * state.scale / old;
        draw();
    }

    canvas.addEventListener('wheel', e => {
        e.preventDefault();
        const r = canvas.getBoundingClientRect();
        zoom(e.deltaY < 0 ? 1.12 : 1 / 1.12, e.clientX - r.left, e.clientY - r.top)
    }, {passive: false});
    canvas.addEventListener('pointerdown', e => {
        canvas.setPointerCapture(e.pointerId);
        state.drag = {x: e.clientX, y: e.clientY, ox: state.ox, oy: state.oy, moved: false}
    });
    canvas.addEventListener('pointermove', e => {
        if (!state.drag) return;
        const dx = e.clientX - state.drag.x, dy = e.clientY - state.drag.y;
        if (Math.abs(dx) + Math.abs(dy) > 3) state.drag.moved = true;
        state.ox = state.drag.ox + dx;
        state.oy = state.drag.oy + dy;
        draw()
    });
    canvas.addEventListener('pointerup', e => {
        if (!state.drag) return;
        if (!state.drag.moved) {
            const r = canvas.getBoundingClientRect(), x = Math.floor((e.clientX - r.left - state.ox) / state.scale + .5),
                z = Math.floor((e.clientY - r.top - state.oy) / state.scale + .5);
            const px = e.clientX - r.left, py = e.clientY - r.top;
            const nearbyPlayer = players().map(player => {
                const p = point(player.x / 16 + .5, player.z / 16 + .5);
                return {player, distance: Math.hypot(px - p.x, py - p.y)};
            }).filter(item => item.distance <= Math.max(10, state.scale * .55))
                .sort((a, b) => a.distance - b.distance)[0];
            if (nearbyPlayer) showDetails('player', nearbyPlayer.player);
            else chooseItem(x, z, e.shiftKey)
        }
        state.drag = null
    });
    document.querySelector('#dimension').onchange = e => {
        state.dimension = e.target.value;
        state.selected = null;
        state.hasFit = false;
        render();
        fit()
    };
    document.querySelector('#fit').onclick = fit;
    document.querySelector('#zoomIn').onclick = () => zoom(1.25);
    document.querySelector('#zoomOut').onclick = () => zoom(.8);
    window.addEventListener('resize', resize);

    async function update() {
        try {
            const response = await fetch('/api/state', {cache: 'no-store'});
            if (!response.ok) throw new Error('HTTP ' + response.status);
            const data = await response.json();
            const previous = state.dimension;
            state.dimensions = Array.isArray(data.dimensions) ? data.dimensions : [];
            if (!state.dimensions.some(d => d.id === state.dimension)) state.dimension = state.dimensions[0]?.id || null;
            const dimensionSelect = document.querySelector('#dimension');
            const dimensionSignature = state.dimensions.map(d => d.id).join('|');
            if (dimensionSelect.dataset.signature !== dimensionSignature) {
                dimensionSelect.replaceChildren();
                for (const dimension of state.dimensions) {
                    const option = document.createElement('option');
                    option.value = dimension.id;
                    option.textContent = dimension.id;
                    dimensionSelect.append(option)
                }
                dimensionSelect.dataset.signature = dimensionSignature;
            }
            dimensionSelect.value = state.dimension || '';
            document.querySelector('#statusDot').className = 'dot ok';
            document.querySelector('#statusText').textContent = 'Connected · ' + new Date().toLocaleTimeString();
            render();
            if (!state.hasFit || previous !== state.dimension) fit();
        } catch (error) {
            document.querySelector('#statusDot').className = 'dot error';
            document.querySelector('#statusText').textContent = 'Disconnected';
            document.querySelector('#message').hidden = false;
            document.querySelector('#message').textContent = 'Cannot access mod data. Check that the Minecraft server is running.';
        }
    }

    resize();
    update();
    setInterval(update, 1000);
})();
