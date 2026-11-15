//! Консольний клієнт прототипу.
//!
//!   shepit keygen <профіль>                 створити акаунт і опублікувати ключі
//!   shepit whoami <профіль>                 показати свій ключ і відбиток
//!   shepit send   <профіль> <ключ> <текст>  надіслати зашифроване повідомлення
//!   shepit recv   <профіль>                 забрати й розшифрувати вхідні
//!
//! Акаунт зберігається у <профіль>.shepit (секретні ключі! етап 0 — файл не зашифрований).
//! Адреса сервера: змінна SHEPIT_SERVER (типово http://127.0.0.1:8443).

use std::{path::PathBuf, process::exit};

use shepit_core::{
    auth::HEADER,
    crypto::{self, Account},
    wire::{self, Envelope, MailboxId, PreKeyBundle},
};

fn server() -> String {
    std::env::var("SHEPIT_SERVER").unwrap_or_else(|_| "http://127.0.0.1:8443".into())
}

fn path(profile: &str) -> PathBuf {
    PathBuf::from(format!("{profile}.shepit"))
}

fn die(msg: impl std::fmt::Display) -> ! {
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

fn load(profile: &str) -> Account {
    Account::load(&path(profile)).unwrap_or_else(|e| match e {
        crypto::Error::Io(_) => die(format!("немає {} — спершу: shepit keygen {profile}", path(profile).display())),
        e => die(e),
    })
}

fn show(acc: &Account) {
    println!("Ключ:     {}", acc.identity_hex());
    println!("Відбиток: {}", acc.fingerprint());
}

fn short(identity_hex: &str) -> String {
    let fp = hex::decode(identity_hex).map(|b| wire::fingerprint(&b)).unwrap_or_default();
    fp.chars().take(11).collect()
}

fn http_err(what: &str, e: ureq::Error) -> ! {
    match e {
        ureq::Error::StatusCode(401) => die(format!("{what}: сервер відхилив підпис (перевір годинник)")),
        ureq::Error::StatusCode(403) => die(format!("{what}: доступ заборонено")),
        e => die(format!("{what}: {e}")),
    }
}

/// Отримати новий сертифікат відправника, якщо поточного немає або він закінчується.
fn ensure_certificate(acc: &mut Account) {
    if !acc.needs_certificate() {
        return;
    }
    let cert_hex = ureq::get(format!("{}/v0/certificate", server()))
        .header(HEADER, acc.auth_header("GET /v0/certificate", b""))
        .call()
        .and_then(|mut r| r.body_mut().read_to_string())
        .unwrap_or_else(|e| http_err("сертифікат", e));
    let cert = hex::decode(cert_hex.trim()).unwrap_or_else(|_| die("сервер повернув некоректний сертифікат"));
    acc.set_certificate(&cert).unwrap_or_else(|e| die(e));
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let arg = |i: usize| args.get(i).map(String::as_str).unwrap_or_else(|| usage());

    match arg(0) {
        "keygen" => {
            let p = path(arg(1));
            if p.exists() {
                die(format!("{} вже існує", p.display()));
            }
            // Закріплюємо корінь довіри сервера при реєстрації (TOFU).
            let root = ureq::get(format!("{}/v0/trust-root", server()))
                .call()
                .and_then(|mut r| r.body_mut().read_to_string())
                .unwrap_or_else(|e| http_err("сервер", e));
            let (mut acc, upload) = Account::create(root.trim()).unwrap_or_else(|e| die(e));
            let body = serde_json::to_vec(&upload).expect("serialize");
            ureq::put(format!("{}/v0/keys", server()))
                .header(HEADER, acc.auth_header("PUT /v0/keys", &body))
                .header("content-type", "application/json")
                .send(&body[..])
                .unwrap_or_else(|e| http_err("публікація ключів", e));
            ensure_certificate(&mut acc);
            acc.save(&p).unwrap_or_else(|e| die(e));
            println!("Створено {} і опубліковано {} одноразових ключів.", p.display(), crypto::ONE_TIME_KEYS);
            show(&acc);
        }
        "whoami" => show(&load(arg(1))),
        "send" => {
            let profile = arg(1);
            let peer = arg(2).to_owned();
            crypto::parse_identity(&peer).unwrap_or_else(|_| die("невалідний ключ отримувача"));
            let text = args[3..].join(" ");
            if text.is_empty() {
                usage();
            }
            let mut acc = load(profile);
            ensure_certificate(&mut acc);
            if !acc.has_session(&peer) {
                let bundle: PreKeyBundle = ureq::get(format!("{}/v0/keys/{peer}", server()))
                    .call()
                    .and_then(|mut r| r.body_mut().read_json())
                    .unwrap_or_else(|e| http_err("ключі отримувача", e));
                acc.start_session(&bundle).unwrap_or_else(|e| die(e));
                println!("Нова сесія з [{}…] (PQXDH)", short(&peer));
            }
            let payload = acc.encrypt(&peer, &text).unwrap_or_else(|e| die(e));
            let to = MailboxId::for_identity(&hex::decode(&peer).unwrap());
            // Надсилання без підпису: сервер не знає, хто відправник (sealed sender).
            ureq::post(format!("{}/v0/send", server()))
                .send_json(&Envelope::new(to, &payload))
                .unwrap_or_else(|e| http_err("надсилання", e));
            acc.save(&path(profile)).unwrap_or_else(|e| die(e));
            println!("Надіслано (sealed sender, {} байтів).", payload.len());
        }
        "recv" => {
            let profile = arg(1);
            let mut acc = load(profile);
            let mb = acc.mailbox().to_hex();
            let envs: Vec<Envelope> = ureq::get(format!("{}/v0/mailbox/{mb}", server()))
                .header(HEADER, acc.auth_header(&format!("GET /v0/mailbox/{mb}"), b""))
                .call()
                .and_then(|mut r| r.body_mut().read_json())
                .unwrap_or_else(|e| http_err("скринька", e));
            if envs.is_empty() {
                println!("Нових повідомлень немає.");
            }
            for env in envs {
                match env.payload_bytes().ok_or(crypto::Error::Malformed("hex")).and_then(|p| acc.decrypt(&p)) {
                    Ok((from, text)) => println!("[{}…] {text}", short(&from)),
                    Err(e) => println!("(відкинуто: {e})"),
                }
            }
            acc.save(&path(profile)).unwrap_or_else(|e| die(e));
        }
        _ => usage(),
    }
}
