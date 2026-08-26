/* tslint:disable */
/* eslint-disable */

export function account_ed25519(account_pickle: string): string;

/**
 * Fallback-ключ (P10 / SEC MED-3) — «последний рубеж», когда одноразовые ключи
 * на сервере кончились. Переиспользуемый, поэтому forward secrecy у первой
 * сессии слабее; подписан тем же каноном `AETHER-OTK-1`, что и обычные OTK.
 */
export function account_generate_fallback_signed(account_pickle: string, user_id: string, device_id: string): string;

export function account_generate_otks(account_pickle: string, count: number): string;

export function account_generate_otks_signed(account_pickle: string, count: number, user_id: string, device_id: string): string;

export function account_identity(account_pickle: string): string;

export function account_new(): string;

export function account_otk_count(account_pickle: string): number;

export function argon2id_key(password: string, salt: Uint8Array, m: number, t: number, p: number): Uint8Array;

export function create_inbound(account_pickle: string, their_identity_b64: string, body_b64: string): string;

export function create_outbound(account_pickle: string, their_identity_b64: string, their_one_time_key_b64: string): string;

export function decrypt(session_pickle: string, message_type: number, body_b64: string): string;

export function encrypt(session_pickle: string, plaintext: string): string;

export function master_public(account_secret_b64: string): string;

/**
 * Идентификатор сессии, которую ЗАВЁЛ БЫ входящий prekey-конверт. Совпал с уже
 * имеющейся — конверт принадлежит ей, новую заводить не надо (иначе каждый
 * повторный prekey жёг бы одноразовый ключ).
 */
export function prekey_session_id(body_b64: string): string;

/**
 * Идентификатор сессии — ключ в локальном хранилище сессий (P10 / SEC MED-4).
 * Совпадает у обеих сторон одной сессии.
 */
export function session_id(session_pickle: string): string;

export function sign_device(account_secret_b64: string, user_id: string, device_id: string, identity_key_b64: string, ed25519_key_b64: string): string;

export function verify_device(master_key_b64: string, user_id: string, device_id: string, identity_key_b64: string, ed25519_key_b64: string, device_sig_b64: string): void;

export function verify_identity(user_id: string, device_id: string, identity_key_b64: string, ed25519_key_b64: string, identity_sig_b64: string): void;

export function verify_prekey_bundle(user_id: string, device_id: string, identity_key_b64: string, ed25519_key_b64: string, identity_sig_b64: string, otk_id: string, otk_b64: string, otk_sig_b64: string): void;

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly memory: WebAssembly.Memory;
    readonly account_ed25519: (a: number, b: number) => [number, number, number, number];
    readonly account_generate_fallback_signed: (a: number, b: number, c: number, d: number, e: number, f: number) => [number, number, number, number];
    readonly account_generate_otks: (a: number, b: number, c: number) => [number, number, number, number];
    readonly account_generate_otks_signed: (a: number, b: number, c: number, d: number, e: number, f: number, g: number) => [number, number, number, number];
    readonly account_identity: (a: number, b: number) => [number, number, number, number];
    readonly account_new: () => [number, number, number, number];
    readonly account_otk_count: (a: number, b: number) => [number, number, number];
    readonly argon2id_key: (a: number, b: number, c: number, d: number, e: number, f: number, g: number) => [number, number, number, number];
    readonly create_inbound: (a: number, b: number, c: number, d: number, e: number, f: number) => [number, number, number, number];
    readonly create_outbound: (a: number, b: number, c: number, d: number, e: number, f: number) => [number, number, number, number];
    readonly decrypt: (a: number, b: number, c: number, d: number, e: number) => [number, number, number, number];
    readonly encrypt: (a: number, b: number, c: number, d: number) => [number, number, number, number];
    readonly master_public: (a: number, b: number) => [number, number, number, number];
    readonly prekey_session_id: (a: number, b: number) => [number, number, number, number];
    readonly session_id: (a: number, b: number) => [number, number, number, number];
    readonly sign_device: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number, j: number) => [number, number, number, number];
    readonly verify_device: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number, j: number, k: number, l: number) => [number, number];
    readonly verify_identity: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number, j: number) => [number, number];
    readonly verify_prekey_bundle: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number, j: number, k: number, l: number, m: number, n: number, o: number, p: number) => [number, number];
    readonly __wbindgen_exn_store: (a: number) => void;
    readonly __externref_table_alloc: () => number;
    readonly __wbindgen_externrefs: WebAssembly.Table;
    readonly __wbindgen_malloc: (a: number, b: number) => number;
    readonly __wbindgen_realloc: (a: number, b: number, c: number, d: number) => number;
    readonly __externref_table_dealloc: (a: number) => void;
    readonly __wbindgen_free: (a: number, b: number, c: number) => void;
    readonly __wbindgen_start: () => void;
}

export type SyncInitInput = BufferSource | WebAssembly.Module;

/**
 * Instantiates the given `module`, which can either be bytes or
 * a precompiled `WebAssembly.Module`.
 *
 * @param {{ module: SyncInitInput }} module - Passing `SyncInitInput` directly is deprecated.
 *
 * @returns {InitOutput}
 */
export function initSync(module: { module: SyncInitInput } | SyncInitInput): InitOutput;

/**
 * If `module_or_path` is {RequestInfo} or {URL}, makes a request and
 * for everything else, calls `WebAssembly.instantiate` directly.
 *
 * @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
 *
 * @returns {Promise<InitOutput>}
 */
export default function __wbg_init (module_or_path?: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;
