/* Copyright (C) 2026 Pilipala · SPDX-License-Identifier: GPL-3.0-only */
(() => {
  const cards = [...document.querySelectorAll('.module')];
  const filters = [...document.querySelectorAll('[data-filter]')];
  const search = document.querySelector('#search');
  let group = '全部';
  const filter = () => {
    const query = search.value.trim().toLocaleLowerCase();
    let count = 0;
    cards.forEach(card => {
      const match = (group === '全部' || card.dataset.group === group)
        && card.textContent.toLocaleLowerCase().includes(query);
      card.hidden = !match;
      if (match) count++;
    });
    filters.forEach(button => button.setAttribute('aria-pressed', String(button.dataset.filter === group)));
    document.querySelector('#result').textContent = `显示 ${count} / ${cards.length} 个模块`;
    document.querySelector('#empty').hidden = count !== 0;
  };
  filters.forEach(button => button.addEventListener('click', () => { group = button.dataset.filter; filter(); }));
  search.addEventListener('input', filter);
  const revealHash = () => {
    const target = document.getElementById(location.hash.slice(1));
    if (target?.classList.contains('module') && target.hidden) {
      group = '全部'; search.value = ''; filter(); target.scrollIntoView();
    }
  };
  window.addEventListener('hashchange', revealHash);
  document.querySelectorAll('.catalog a').forEach(link => link.addEventListener('click', () => {
    group = '全部'; search.value = ''; filter();
  }));
  document.querySelectorAll('.thumb').forEach(button => button.addEventListener('click', () => {
    const card = button.closest('.module');
    const img = card.querySelector('.main-shot');
    img.src = button.dataset.src; img.alt = button.dataset.caption;
    const link = card.querySelector('.zoom');
    link.href = button.dataset.src; link.setAttribute('aria-label', `放大：${button.dataset.caption}`);
    card.querySelector('figcaption').textContent = button.dataset.caption;
    card.querySelectorAll('.thumb').forEach(other => other.setAttribute('aria-pressed', String(other === button)));
  }));
  const dialog = document.querySelector('#lightbox');
  document.querySelectorAll('.zoom').forEach(link => link.addEventListener('click', event => {
    if (!dialog.showModal || event.ctrlKey || event.metaKey || event.shiftKey) return;
    event.preventDefault();
    const label = link.querySelector('img').alt;
    document.querySelector('#viewer-title').textContent = label;
    const img = document.querySelector('#viewer-img'); img.src = link.href; img.alt = label;
    document.querySelector('#original').href = link.href;
    dialog.showModal();
  }));
  document.querySelector('#close-viewer').addEventListener('click', () => dialog.close());
  dialog.addEventListener('click', event => {
    const bounds = dialog.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dialog.close();
  });
  revealHash();
})();
