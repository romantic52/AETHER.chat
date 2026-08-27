-- RB-2: сколько уже существует ПУБЛИЧНЫХ ОБЫЧНЫХ ГРУПП (не каналов).
--
-- Зачем: set_group_public читает is_channel и не использует его, поэтому
-- публичной можно сделать любую группу — и тогда её 32-байтный ключ лежит у
-- сервера открытым текстом, вопреки обещанию README, что сервер знает ключ
-- только у публичных КАНАЛОВ.
--
-- От этого числа зависит выбор:
--   0 строк   → чинить кодом (требовать is_channel = 1) бесплатно, никто не
--               сломается;
--   > 0 строк → у живых групп при таком запрете отвалится публичный доступ;
--               нужен план миграции (превратить в каналы, разпубличить с
--               уведомлением владельцев) либо вариант «переписать README».
--
-- Запускать на КОПИИ боевой базы:
--   psql -h <host> -U <user> -d <db> -f server/count_public_groups.sql

\echo '--- 1. Сводка: публичные группы против публичных каналов ---'
SELECT
    CASE WHEN is_channel = 1 THEN 'канал (по документации — ok)'
         ELSE 'ОБЫЧНАЯ ГРУППА (нарушение README)' END AS вид,
    COUNT(*) AS сколько
FROM groups
WHERE COALESCE(join_key_b64, '') <> ''
GROUP BY is_channel
ORDER BY is_channel;

\echo ''
\echo '--- 2. Сами нарушители: публичные НЕ-каналы ---'
SELECT g.id,
       g.name,
       g.username,
       g.owner_id,
       g.created_at,
       (SELECT COUNT(*) FROM group_members m WHERE m.group_id = g.id) AS участников
FROM groups g
WHERE COALESCE(g.join_key_b64, '') <> ''
  AND COALESCE(g.is_channel, 0) <> 1
ORDER BY участников DESC, g.created_at;

\echo ''
\echo '--- 3. Итог одной строкой ---'
SELECT COUNT(*) AS "публичных обычных групп"
FROM groups
WHERE COALESCE(join_key_b64, '') <> ''
  AND COALESCE(is_channel, 0) <> 1;
