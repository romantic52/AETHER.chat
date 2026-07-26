#!/usr/bin/env python3
"""Bounded server-admin helper for the AETHER backend.

Applies ONLY the named, hardcoded patches below to /root/secure_messenger/server/main.py
on the production host, syntax-checks, and restarts the service. It cannot run
arbitrary remote commands — every action is a reviewed entry in PATCHES/ACTIONS.

Usage:  python scripts/srv_admin.py <action>
        python scripts/srv_admin.py list
"""
import os
import sys


def _load_env():
    """Читает креды из .env в корне репозитория (AETHER_SSH_HOST/USER/PASSWORD)."""
    env_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".env")
    values = {}
    try:
        with open(env_path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    values[k.strip()] = v.strip().strip('"').strip("'")
    except OSError:
        pass
    return values


_ENV = _load_env()
HOST = os.environ.get("AETHER_SSH_HOST") or _ENV.get("AETHER_SSH_HOST", "144.31.181.10")
USER = os.environ.get("AETHER_SSH_USER") or _ENV.get("AETHER_SSH_USER", "root")
PASSWORD = os.environ.get("AETHER_SSH_PASSWORD") or _ENV.get("AETHER_SSH_PASSWORD")
if not PASSWORD:
    sys.exit("AETHER_SSH_PASSWORD не найден ни в окружении, ни в .env")
MAIN = "/root/secure_messenger/server/main.py"

# Each patch: unique marker already in the file to anchor the insert (the new code
# is inserted right before it), a `guard` substring that means "already applied",
# and the code block to insert.
PATCHES = {
    "role-endpoint": {
        "guard": 'members/{user_id}/role',
        "anchor": '# --- (#12) Edit group ---',
        "code": '''# --- Change member role (owner only): promote/demote admin ---
@app.put("/groups/{group_id}/members/{user_id}/role")
def set_member_role(group_id: str, user_id: str, role: str, current_user: str = Depends(get_current_user)) -> dict:
    if role not in ("admin", "member"):
        raise HTTPException(400, "role must be admin or member")
    with db_conn() as cur:
        cur.execute("SELECT owner_id FROM groups WHERE LOWER(id) = LOWER(%s)", (group_id,))
        g = cur.fetchone()
        if not g:
            raise HTTPException(404, "Group not found")
        if g["owner_id"].lower() != current_user.lower():
            raise HTTPException(403, "Only the owner can change roles")
        if user_id.lower() == g["owner_id"].lower():
            raise HTTPException(400, "Owner role cannot be changed")
        cur.execute("SELECT 1 FROM group_members WHERE LOWER(group_id) = LOWER(%s) AND LOWER(user_id) = LOWER(%s)", (group_id, user_id))
        if not cur.fetchone():
            raise HTTPException(404, "Not a member")
        cur.execute("UPDATE group_members SET role = %s WHERE LOWER(group_id) = LOWER(%s) AND LOWER(user_id) = LOWER(%s)", (role, group_id, user_id))
    return {"ok": True, "role": role}


''',
    },
}


def run(cli, cmd):
    _in, out, err = cli.exec_command(cmd, timeout=60)
    return out.read().decode("utf-8", "replace"), err.read().decode("utf-8", "replace")


def apply_patch(cli, name):
    import textwrap
    p = PATCHES[name]
    # Read the remote file, patch locally in a tiny python invocation on the host.
    remote = f'''python3 - <<'RPYEOF'
import os, py_compile
p = "{MAIN}"
src = open(p, encoding="utf-8").read()
bak = p + ".bak-srvadmin"
if not os.path.exists(bak): open(bak, "w", encoding="utf-8").write(src)
guard = {p["guard"]!r}
if guard in src:
    print("ALREADY_APPLIED")
else:
    anchor = {p["anchor"]!r}
    assert anchor in src, "anchor not found"
    code = {p["code"]!r}
    src = src.replace(anchor, code + anchor, 1)
    open(p, "w", encoding="utf-8").write(src)
    print("PATCHED")
py_compile.compile(p, doraise=True)
print("SYNTAX_OK")
RPYEOF'''
    out, err = run(cli, remote)
    sys.stdout.write(out)
    if err.strip():
        sys.stdout.write("\n--STDERR--\n" + err)
    return "SYNTAX_OK" in out


def main():
    if len(sys.argv) < 2 or sys.argv[1] == "list":
        print("actions:", ", ".join(PATCHES), "| restart | status")
        return
    action = sys.argv[1]
    import paramiko
    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(HOST, username=USER, password=PASSWORD, timeout=20)
    try:
        if action == "restart":
            out, _ = run(cli, "systemctl restart secure_messenger && sleep 2 && systemctl is-active secure_messenger")
            sys.stdout.write(out)
        elif action == "status":
            out, _ = run(cli, "systemctl is-active secure_messenger")
            sys.stdout.write(out)
        elif action in PATCHES:
            if apply_patch(cli, action):
                out, _ = run(cli, "systemctl restart secure_messenger && sleep 2 && systemctl is-active secure_messenger")
                sys.stdout.write("restart: " + out)
        else:
            print("unknown action:", action, "| known:", ", ".join(PATCHES), "restart status")
    finally:
        cli.close()


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    main()
