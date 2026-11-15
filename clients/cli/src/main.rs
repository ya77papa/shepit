//! Консольний клієнт прототипу.
//!
//!   shepit keygen <профіль>                 створити ключ (файл <профіль>.key)
//!   shepit whoami <профіль>                 показати публічний ключ і відбиток
//!   shepit send   <профіль> <ключ> <текст>  надіслати повідомлення
//!   shepit recv   <профіль>                 забрати вхідні
//!
//! Адреса сервера: змінна SHEPIT_SERVER (типово http://127.0.0.1:8443).
//! УВАГА: етап 0 — повідомлення підписані, але ще не зашифровані.

use std::{fs, path::PathBuf, process::exit};

use shepit_core::{
    envelope::Envelope,
    identity::{Identity, PublicIdentity},
    message,
};

fn server() -> String {
    std::env::var("SHEPIT_SERVER").unwrap_or_else(|_| "http://127.0.0.1:8443".into())
}

fn key_path(profile: &str) -> PathBuf {
    PathBuf::from(format!("{profile}.key"))
}

fn load(profile: &str) -> Identity {
    let path = key_path(profile);
    let hex_secret = fs::read_to_string(&path).unwrap_or_else(|_| {
        die(&format!("немає {} — спершу: shepit keygen {profile}", path.display()))
    });
    let secret: [u8; 32] = hex::decode(hex_secret.trim())
        .ok()
        .and_then(|v| v.try_into().ok())
        .unwrap_or_else(|| die("пошкоджений файл ключа"));
    Identity::from_secret(&secret)
}

fn show(id: &PublicIdentity) {
    println!("Ключ:     {}", hex::encode(id.to_bytes()));
    println!("Відбиток: {}", id.fingerprint());
}

fn die(msg: &str) -> ! {
    eprintln!("Помилка: {msg}");
    exit(1)
}

fn usage() -> ! {
    eprintln!(
        "Використання:\n  shepit keygen <профіль>\n  shepit whoami <профіль>\n  \
         shepit send <профіль> <ключ-отримувача> <текст>\n  shepit recv <профіль>"
    );
    exit(2)
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let arg = |i: usize| args.get(i).map(String::as_str).unwrap_or_else(|| usage());

    match arg(0) {
        "keygen" => {
            let profile = arg(1);
            let path = key_path(profile);
            if path.exists() {
                die(&format!("{} вже існує", path.display()));
            }
            let id = Identity::generate();
            fs::write(&path, hex::encode(id.secret_bytes())).unwrap_or_else(|e| die(&e.to_string()));
            println!("Створено {} (секретний ключ, нікому не показуй)", path.display());
            show(&id.public());
        }
        "whoami" => show(&load(arg(1)).public()),
        "send" => {
            let me = load(arg(1));
            let to: [u8; 32] = hex::decode(arg(2))
                .ok()
                .and_then(|v| v.try_into().ok())
                .unwrap_or_else(|| die("ключ отримувача — 64 hex-символи"));
            let to = PublicIdentity::from_bytes(&to).unwrap_or_else(|_| die("невалідний ключ"));
            let text = args[3..].join(" ");
            if text.is_empty() {
                usage();
            }
            let env = Envelope::new(to.mailbox(), message::seal(&me, &text));
            ureq::post(format!("{}/v0/send", server()))
                .send_json(&env)
                .unwrap_or_else(|e| die(&format!("сервер: {e}")));
            println!("Надіслано.");
        }
        "recv" => {
            let me = load(arg(1));
            let url = format!("{}/v0/mailbox/{}", server(), me.public().mailbox().to_hex());
            let envs: Vec<Envelope> = ureq::get(url)
                .call()
                .and_then(|mut r| r.body_mut().read_json())
                .unwrap_or_else(|e| die(&format!("сервер: {e}")));
            if envs.is_empty() {
                println!("Нових повідомлень немає.");
            }
            for env in envs {
                match message::open(&env.payload) {
                    Ok(m) => println!("[{}…] {}", &m.from.fingerprint()[..11], m.text),
                    Err(e) => println!("(відкинуто: {e:?})"),
                }
            }
        }
        _ => usage(),
    }
}
