//! Realtime-слой ядра: WebSocket-клиент relay-сервера (`/ws`). Один на все
//! платформы — раньше каждый клиент держал свой WS (Android: OkHttp в
//! AetherService, веб: браузерный WebSocket). Зеркалит протокол `server/main.py`:
//!
//! - подключение: `wss://host/ws?token=<token>`;
//! - входящие: `{type:"new_message"}`, `{type:"typing", sender_id}`,
//!   `{type:"webrtc_offer|answer|ice|hangup|busy", ...}`;
//! - исходящие: `{type:"typing", recipient_id}`, сырые webrtc-сигналы;
//! - авто-реконнект при обрыве, keepalive-ping.
//!
//! Платформа реализует `WsListener` (callback-интерфейс) и получает события.
//! Внутри — отдельный поток-насос (single-thread pump): блокирующий
//! `tungstenite` с коротким read-timeout, чтобы в одном цикле и читать входящие,
//! и отправлять исходящие из канала.

use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tungstenite::stream::MaybeTlsStream;
use tungstenite::{Message, WebSocket};

/// События realtime-канала. Реализуется платформой (Kotlin/Swift).
#[uniffi::export(callback_interface)]
pub trait WsListener: Send + Sync {
    /// Соединение установлено.
    fn on_connected(&self);
    /// Соединение потеряно (далее ядро попробует переподключиться, если не disconnect).
    fn on_disconnected(&self);
    /// Сервер сообщил о новом сообщении — пора подтянуть inbox.
    /// sender_id — отправитель (для имени в уведомлении); пустая строка, если сервер не прислал.
    fn on_new_message(&self, sender_id: String);
    /// Собеседник печатает.
    fn on_typing(&self, sender_id: String);
    /// WebRTC-сигнал (offer/answer/ice/hangup/busy) — полный JSON как пришёл.
    fn on_webrtc_signal(&self, json: String);
}

type Sock = WebSocket<MaybeTlsStream<TcpStream>>;

struct WsInner {
    tx: Option<Sender<String>>,
    stop: Option<Arc<AtomicBool>>,
    handle: Option<JoinHandle<()>>,
}

/// WebSocket-клиент relay-сервера. Держит фоновый поток-насос и канал исходящих.
#[derive(uniffi::Object)]
pub struct WsClient {
    ws_base: String,
    inner: Mutex<WsInner>,
}

#[uniffi::export]
impl WsClient {
    /// base_url — обычный http(s)-адрес сервера (как у ApiClient); схема сама
    /// превращается в ws(s).
    #[uniffi::constructor]
    pub fn new(base_url: String) -> Arc<Self> {
        let b = base_url.trim_end_matches('/');
        let ws_base = if let Some(rest) = b.strip_prefix("https://") {
            format!("wss://{}", rest)
        } else if let Some(rest) = b.strip_prefix("http://") {
            format!("ws://{}", rest)
        } else {
            b.to_string()
        };
        Arc::new(Self {
            ws_base,
            inner: Mutex::new(WsInner { tx: None, stop: None, handle: None }),
        })
    }

    /// Подключиться с токеном сессии. Повторный вызов перезапускает соединение.
    pub fn connect(&self, token: String, listener: Box<dyn WsListener>) {
        let mut inner = self.inner.lock().unwrap();
        // Старый насос гасим, чтобы не плодить соединения.
        if let Some(stop) = inner.stop.take() {
            stop.store(true, Ordering::SeqCst);
        }
        let stop = Arc::new(AtomicBool::new(false));
        let (tx, rx) = mpsc::channel::<String>();
        let url = format!("{}/ws?token={}", self.ws_base, token);
        let stop_c = stop.clone();
        let handle = thread::spawn(move || pump(url, rx, listener, stop_c));
        inner.tx = Some(tx);
        inner.stop = Some(stop);
        inner.handle = Some(handle);
    }

    /// Сообщить собеседнику, что мы печатаем.
    pub fn send_typing(&self, recipient_id: String) {
        self.push(json!({ "type": "typing", "recipient_id": recipient_id }).to_string());
    }

    /// Отправить webrtc-сигнал (offer/answer/ice/hangup/busy) — передаётся как есть.
    pub fn send_webrtc_signal(&self, json: String) {
        self.push(json);
    }

    /// Отправить произвольное JSON-сообщение в сокет.
    pub fn send_raw(&self, json: String) {
        self.push(json);
    }

    /// Подключён ли насос (есть активный канал отправки).
    pub fn is_active(&self) -> bool {
        self.inner.lock().unwrap().tx.is_some()
    }

    /// Закрыть соединение и остановить реконнекты.
    pub fn disconnect(&self) {
        let mut inner = self.inner.lock().unwrap();
        if let Some(stop) = inner.stop.take() {
            stop.store(true, Ordering::SeqCst);
        }
        inner.tx = None;
        // Поток сам закроет сокет в течение read-timeout; join не делаем, чтобы
        // не блокировать вызывающий (UI) поток.
        inner.handle = None;
    }
}

impl WsClient {
    fn push(&self, msg: String) {
        if let Some(tx) = self.inner.lock().unwrap().tx.as_ref() {
            let _ = tx.send(msg);
        }
    }
}

/// Поток-насос: держит соединение, переподключается, читает входящие и
/// отправляет исходящие из канала.
fn pump(url: String, rx: Receiver<String>, listener: Box<dyn WsListener>, stop: Arc<AtomicBool>) {
    while !stop.load(Ordering::SeqCst) {
        match tungstenite::connect(&url) {
            Ok((mut socket, _resp)) => {
                set_read_timeout(&mut socket, Some(Duration::from_millis(250)));
                listener.on_connected();
                run_session(&mut socket, &rx, &listener, &stop);
                let _ = socket.close(None);
                listener.on_disconnected();
            }
            Err(_) => {
                listener.on_disconnected();
            }
        }
        if stop.load(Ordering::SeqCst) {
            break;
        }
        // Бэкофф перед реконнектом (~3 c), но реагируем на stop быстро.
        for _ in 0..30 {
            if stop.load(Ordering::SeqCst) {
                return;
            }
            thread::sleep(Duration::from_millis(100));
        }
    }
}

/// Один сеанс активного соединения: цикл чтения/записи до обрыва или stop.
fn run_session(socket: &mut Sock, rx: &Receiver<String>, listener: &Box<dyn WsListener>, stop: &Arc<AtomicBool>) {
    let mut last_ping = Instant::now();
    loop {
        if stop.load(Ordering::SeqCst) {
            return;
        }
        // 1) Отправляем накопившиеся исходящие.
        while let Ok(msg) = rx.try_recv() {
            if socket.send(Message::Text(msg)).is_err() {
                return;
            }
        }
        // 2) Keepalive-ping (как pingInterval 20s у OkHttp).
        if last_ping.elapsed() > Duration::from_secs(20) {
            if socket.send(Message::Ping(Vec::new())).is_err() {
                return;
            }
            last_ping = Instant::now();
        }
        // 3) Читаем входящие (read-timeout ~250 мс → не блокируемся навечно).
        match socket.read() {
            Ok(Message::Text(t)) => dispatch(listener, &t),
            Ok(Message::Close(_)) => return,
            // Ping/Pong/Binary tungstenite обрабатывает сам (pong на ping).
            Ok(_) => {}
            Err(tungstenite::Error::Io(e))
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::TimedOut =>
            {
                // Нет данных за окно таймаута — нормальная пауза, продолжаем.
            }
            Err(_) => return,
        }
    }
}

/// Разбор входящего JSON и вызов нужного метода слушателя.
fn dispatch(listener: &Box<dyn WsListener>, text: &str) {
    let v: Value = match serde_json::from_str(text) {
        Ok(v) => v,
        Err(_) => return,
    };
    let t = v["type"].as_str().unwrap_or("");
    if t == "new_message" {
        listener.on_new_message(v["sender_id"].as_str().unwrap_or("").to_string());
    } else if t == "typing" {
        listener.on_typing(v["sender_id"].as_str().unwrap_or("").to_string());
    } else if t.starts_with("webrtc_") {
        listener.on_webrtc_signal(text.to_string());
    }
    // Прочие типы игнорируем (как и Android).
}

/// Выставляет read-timeout на нижележащий TcpStream (нужно для single-thread pump).
fn set_read_timeout(socket: &mut Sock, dur: Option<Duration>) {
    match socket.get_mut() {
        MaybeTlsStream::Plain(s) => {
            let _ = s.set_read_timeout(dur);
        }
        MaybeTlsStream::Rustls(s) => {
            let _ = s.sock.set_read_timeout(dur);
        }
        _ => {}
    }
}
