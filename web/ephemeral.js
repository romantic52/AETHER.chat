// Исчезающие сообщения в вебе.
//
// До этого модуля веб не знал про поле `ephemeral` в конверте: сообщение,
// которое отправитель считал временным, приходило обычным текстом и лежало
// вечно. Интерфейс на другой стороне обещал «просмотр один раз» — и обещание
// не выполнялось. Хуже отсутствующей функции только функция, о которой врут.
//
// Разбор описания идёт через общее ядро (ratchet-core), а не своим парсером:
// зажим враждебных значений обязан совпадать с native до последнего правила.
//
// Ограничение, которое надо помнить: содержимое уже на устройстве. Это
// заслонка, а не криптография. Она мешает сообщению показаться само — через
// плечо, в списке чатов, на разблокированном экране, — и удаляет его по сроку.
// От человека, решившего сохранить текст, она не защищает, и делать вид,
// что защищает, нельзя.

(function (global) {
    'use strict';

    const STATE_PREFIX = 'ephemeral_state_';
    /// Как часто проверяем истёкшие. Секунда — компромисс: чаще незачем,
    /// реже заметно на коротких сроках.
    const SWEEP_INTERVAL_MS = 1000;
    /// Если триггер «первое открытие», а срок не задан, сообщение не должно
    /// висеть вечно: даём разумное окно на прочтение.
    const DEFAULT_OPEN_TTL_MS = 5 * 60 * 1000;

    let ratchetApi = null;
    let sweepTimer = null;

    /// Состояние живёт в пространстве: одинаковый логин на двух серверах не
    /// должен делить отметки просмотров.
    function stateKey() {
        return global.spaceKey ? global.spaceKey(STATE_PREFIX) : STATE_PREFIX + 'default';
    }

    function loadState() {
        try {
            return JSON.parse(localStorage.getItem(stateKey()) || '{}') || {};
        } catch (_) {
            return {};
        }
    }

    function saveState(state) {
        try {
            localStorage.setItem(stateKey(), JSON.stringify(state));
        } catch (_) {
            // Переполнение хранилища не должно ронять чат: хуже всего здесь
            // потерять отметку и показать сообщение второй раз.
        }
    }

    function entry(messageId) {
        return loadState()[messageId] || null;
    }

    function putEntry(messageId, value) {
        const state = loadState();
        state[messageId] = value;
        saveState(state);
    }

    /// Описание из нагрузки. null — сообщение обычное.
    function specOf(payloadObj) {
        if (!payloadObj || !ratchetApi || !ratchetApi.ephemeral_from_payload) return null;
        try {
            const raw = ratchetApi.ephemeral_from_payload(JSON.stringify(payloadObj));
            return raw ? JSON.parse(raw) : null;
        } catch (_) {
            return null;
        }
    }

    function isPurged(messageId) {
        const e = entry(messageId);
        return !!e && e.state === 'PURGED';
    }

    function viewedByPeer(messageId) {
        const e = entry(messageId);
        return !!e && e.state === 'VIEWED_BY_PEER';
    }

    /// Отметка «получатель открыл» на стороне отправителя.
    /// Ничего не сжигает: срок жизни принадлежит копии получателя.
    function markViewedByPeer(messageId) {
        const current = entry(messageId) || {};
        putEntry(messageId, {
            state: 'VIEWED_BY_PEER',
            openedTs: current.openedTs || Date.now(),
            expiresTs: current.expiresTs || null,
            views: Math.max(current.views || 0, 1),
        });
    }

    /// Засчитать просмотр у получателя. false — показывать больше нечего.
    function open(messageId, spec) {
        if (!spec) return true;
        const current = entry(messageId);
        if (current && current.state === 'PURGED') return false;
        const views = (current && current.views) || 0;
        if (spec.view_limit != null && views >= spec.view_limit) return false;

        let expiresTs = current ? current.expiresTs : null;
        if (spec.trigger === 'ABSOLUTE') {
            expiresTs = spec.absolute_ms || null;
        } else if (spec.trigger === 'FIRST_OPEN' && !expiresTs) {
            expiresTs = Date.now() + (spec.ttl_seconds > 0 ? spec.ttl_seconds * 1000 : DEFAULT_OPEN_TTL_MS);
        }
        putEntry(messageId, {
            state: expiresTs ? 'COUNTDOWN' : 'OPENED',
            openedTs: (current && current.openedTs) || Date.now(),
            expiresTs,
            views: views + 1,
        });
        return true;
    }

    /// Просмотр закрыт. Для «одного раза» и триггера CLOSE это конец.
    function closeView(messageId, spec) {
        if (!spec) return;
        const current = entry(messageId);
        if (!current || current.state === 'PURGED') return;
        if (spec.view_limit != null && current.views >= spec.view_limit) {
            purge(messageId);
            return;
        }
        if (spec.trigger === 'CLOSE') {
            putEntry(messageId, Object.assign({}, current, {
                state: 'COUNTDOWN',
                expiresTs: Date.now() + spec.ttl_seconds * 1000,
            }));
        }
    }

    function purge(messageId) {
        putEntry(messageId, { state: 'PURGED', openedTs: null, expiresTs: null, views: 0 });
        if (typeof global.onEphemeralPurged === 'function') {
            global.onEphemeralPurged(messageId);
        }
    }

    /// Обход истёкших. Возвращает список удалённых — вызывающий обновляет вид.
    function sweep(now) {
        const at = now || Date.now();
        const state = loadState();
        const purged = [];
        Object.keys(state).forEach(id => {
            const e = state[id];
            if (!e || e.state === 'PURGED' || e.state === 'VIEWED_BY_PEER') return;
            if (e.expiresTs && e.expiresTs <= at) purged.push(id);
        });
        purged.forEach(purge);
        return purged;
    }

    function startSweeping() {
        if (sweepTimer) return;
        sweepTimer = setInterval(() => sweep(), SWEEP_INTERVAL_MS);
    }

    function stopSweeping() {
        if (!sweepTimer) return;
        clearInterval(sweepTimer);
        sweepTimer = null;
    }

    /// Сбросить состояние пространства: при выходе отметки не должны пережить
    /// аккаунт и всплыть у следующего.
    function resetSpace() {
        try {
            localStorage.removeItem(stateKey());
        } catch (_) { /* нечего чистить */ }
    }

    function attachRatchet(api) {
        ratchetApi = api;
    }

    global.aetherEphemeral = {
        attachRatchet,
        specOf,
        open,
        closeView,
        purge,
        sweep,
        startSweeping,
        stopSweeping,
        isPurged,
        viewedByPeer,
        markViewedByPeer,
        resetSpace,
        entry,
    };
})(window);
