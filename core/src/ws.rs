//! WebSocket-клиент для реалтайма: typing, presence, мгновенная доставка, webrtc-сигналинг.
//! Соединение живёт в фоновом потоке; события отдаются в Swift через WsListener (callback).

use crate::CoreError;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tungstenite::Message;

/// Callback-интерфейс: Swift реализует его, получая уже распарсенные события.
#[uniffi::export(callback_interface)]
pub trait WsListener: Send + Sync {
    /// Соединение установлено.
    fn on_open(&self);
    /// Пришло текстовое событие (сырой JSON, как прислал сервер).
    fn on_event(&self, json: String);
    /// Соединение закрыто (Swift решает, переподключаться ли).
    fn on_close(&self);
}

enum Cmd {
    Send(String),
    Close,
}

#[derive(uniffi::Object)]
pub struct WsClient {
    tx: Mutex<Option<Sender<Cmd>>>,
    active: Arc<AtomicBool>,
}

#[uniffi::export]
impl WsClient {
    #[uniffi::constructor]
    pub fn new() -> Self {
        WsClient { tx: Mutex::new(None), active: Arc::new(AtomicBool::new(false)) }
    }

    /// Подключиться к wss://host/ws?token=... . Неблокирующий: работает в фоновом потоке.
    pub fn connect(&self, url: String, listener: Box<dyn WsListener>) -> Result<(), CoreError> {
        self.disconnect();
        let (tx, rx) = channel::<Cmd>();
        *self.tx.lock().unwrap() = Some(tx.clone());
        let active = self.active.clone();
        let listener: Arc<dyn WsListener> = Arc::from(listener);

        std::thread::spawn(move || {
            let mut sock = match tungstenite::connect(&url) {
                Ok((s, _resp)) => s,
                Err(_) => {
                    active.store(false, Ordering::SeqCst);
                    listener.on_close();
                    return;
                }
            };
            // Неблокирующее чтение, чтобы обслуживать и rx-команды, и ping.
            if let tungstenite::stream::MaybeTlsStream::Rustls(ref s) = sock.get_ref() {
                let _ = s.get_ref().set_read_timeout(Some(Duration::from_millis(200)));
            }
            active.store(true, Ordering::SeqCst);
            listener.on_open();

            let mut last_ping = std::time::Instant::now();
            loop {
                // Исходящие команды.
                while let Ok(cmd) = rx.try_recv() {
                    match cmd {
                        Cmd::Send(text) => {
                            if sock.send(Message::Text(text)).is_err() {
                                active.store(false, Ordering::SeqCst);
                                listener.on_close();
                                return;
                            }
                        }
                        Cmd::Close => {
                            let _ = sock.close(None);
                            active.store(false, Ordering::SeqCst);
                            listener.on_close();
                            return;
                        }
                    }
                }
                // Ping каждые 25с — сервер отвечает "pong" (текст).
                if last_ping.elapsed() >= Duration::from_secs(25) {
                    let _ = sock.send(Message::Text("ping".into()));
                    last_ping = std::time::Instant::now();
                }
                // Входящие.
                match sock.read() {
                    Ok(Message::Text(t)) => {
                        if t != "pong" {
                            listener.on_event(t);
                        }
                    }
                    Ok(Message::Ping(p)) => { let _ = sock.send(Message::Pong(p)); }
                    Ok(Message::Close(_)) => {
                        active.store(false, Ordering::SeqCst);
                        listener.on_close();
                        return;
                    }
                    Ok(_) => {}
                    Err(tungstenite::Error::Io(ref e))
                        if e.kind() == std::io::ErrorKind::WouldBlock
                            || e.kind() == std::io::ErrorKind::TimedOut => {}
                    Err(_) => {
                        active.store(false, Ordering::SeqCst);
                        listener.on_close();
                        return;
                    }
                }
            }
        });
        Ok(())
    }

    fn send_raw_internal(&self, text: String) -> Result<(), CoreError> {
        let guard = self.tx.lock().unwrap();
        match guard.as_ref() {
            Some(tx) => tx.send(Cmd::Send(text)).map_err(|_| CoreError::Ws { msg: "ws закрыт".into() }),
            None => Err(CoreError::Ws { msg: "нет соединения".into() }),
        }
    }

    /// Произвольный JSON-фрейм.
    pub fn send_raw(&self, json: String) -> Result<(), CoreError> {
        self.send_raw_internal(json)
    }

    pub fn send_typing(&self, recipient_id: String) -> Result<(), CoreError> {
        self.send_raw_internal(
            serde_json::json!({ "type": "typing", "recipient_id": recipient_id }).to_string(),
        )
    }

    /// WebRTC-сигнал: type — webrtc_offer|webrtc_answer|webrtc_ice|webrtc_hangup|webrtc_busy.
    /// extra_json — доп. поля (sdp/candidate/sig_id/...) как JSON-объект.
    pub fn send_webrtc_signal(&self, signal_type: String, recipient_id: String, extra_json: String) -> Result<(), CoreError> {
        let mut obj: serde_json::Value = serde_json::from_str(&extra_json).unwrap_or(serde_json::json!({}));
        obj["type"] = signal_type.into();
        obj["recipient_id"] = recipient_id.into();
        self.send_raw_internal(obj.to_string())
    }

    pub fn is_active(&self) -> bool {
        self.active.load(Ordering::SeqCst)
    }

    pub fn disconnect(&self) {
        if let Some(tx) = self.tx.lock().unwrap().take() {
            let _ = tx.send(Cmd::Close);
        }
        self.active.store(false, Ordering::SeqCst);
    }
}
