import Foundation

// Адреса активного пространства для кода, который строит URL напрямую.
//
// Раньше это была константа CoreClient.baseURL, зашитая в сборку: пока сервер
// был один, вопроса не возникало. С пользовательскими серверами адрес стал
// величиной времени выполнения, и читают его отовсюду — из акторов, из вьюх,
// из фоновых задач. Поэтому не @MainActor и не actor, а простой замок:
// чтение обязано работать в любом контексте и не быть await.
//
// Здесь ДВА адреса, и путать их нельзя:
//   origin  — корень сервера, им строятся ссылки на файлы (/avatars, /download);
//   apiBase — то, что сервер объявил как api_url (у нового — с /api/v1).
enum ServerContext {
    private static let lock = NSLock()

    private static var _origin = Secrets.baseURL
    private static var _apiBase = Secrets.baseURL
    private static var _wsEndpoint = Secrets.wsBaseURL + "/ws"
    private static var _serverId = ServerRegistry.officialPlaceholderId
    private static var _dbFileName = "aether.sqlite"

    /// Корень сервера: https://host[:port]. Для медиа и аватарок.
    static var origin: String { read { _origin } }
    /// База API активного пространства. Для прямых HTTP-запросов мимо ядра.
    static var apiBase: String { read { _apiBase } }
    /// ПОЛНЫЙ адрес WebSocket, как объявил сервер (уже включает /ws).
    /// Дописывать к нему путь нельзя — получится /ws/ws.
    static var wsEndpoint: String { read { _wsEndpoint } }
    /// server_id активного пространства — им же именуются ключи Keychain.
    static var serverId: String { read { _serverId } }
    /// Имя файла локальной базы активного пространства.
    static var dbFileName: String { read { _dbFileName } }

    static func set(origin: String, apiBase: String, wsEndpoint: String,
                    serverId: String, dbFileName: String) {
        lock.lock()
        _origin = origin
        _apiBase = apiBase
        _wsEndpoint = wsEndpoint
        _serverId = serverId
        _dbFileName = dbFileName
        lock.unlock()
    }

    private static func read<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    /// Адрес сокета, выведенный из origin, когда сервер не назвал свой явно.
    static func websocketEndpoint(for origin: String) -> String {
        if origin.hasPrefix("https://") {
            return "wss://" + origin.dropFirst("https://".count) + "/ws"
        }
        if origin.hasPrefix("http://") {
            return "ws://" + origin.dropFirst("http://".count) + "/ws"
        }
        return origin + "/ws"
    }
}
