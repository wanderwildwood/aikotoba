#!/usr/bin/env python3
"""A KeePass test vault made by pykeepass, a KDBX implementation independent of this app's.

    make-test-vault.py OUT.kdbx PASSWORD [--light] [--argon2id] [--entries N]

Made-up people and made-up secrets only. --light uses tiny Argon2 settings for the JVM tests;
without it the settings are KeePassXC-like (Argon2d, 64 MiB, 10 passes, 2 lanes). --argon2id
switches the variant; --entries N adds N more plain sign-ins, to see a vault of a real size.
Needs pykeepass (pip install pykeepass).
"""
import sys
from pykeepass import create_database

out, password = sys.argv[1], sys.argv[2]
light = "--light" in sys.argv
argon2id = "--argon2id" in sys.argv
extra = int(sys.argv[sys.argv.index("--entries") + 1]) if "--entries" in sys.argv else 0

kp = create_database(out, password=password)
params = kp.kdbx.header.value.dynamic_header.kdf_parameters.data.dict
params["M"].value = (1 if light else 64) * 1024 * 1024
params["I"].value = 2 if light else 10
params["P"].value = 1 if light else 2
if argon2id:
    params["$UUID"].value = bytes.fromhex("9e298b1956db4773b23dfc3ec6f0a1e6")

root = kp.root_group
work = kp.add_group(root, "Work")

mail = kp.add_entry(root, "Example Mail", "ada.whitlock@example.org", "correct-horse-battery",
                    url="https://mail.example.org")
mail.otp = ("otpauth://totp/Example%20Mail:ada.whitlock%40example.org"
                         "?secret=JBSWY3DPEHPK3PXP&issuer=Example%20Mail&period=30&digits=6")

bank = kp.add_entry(root, "River Bank", "tomas.reyes", "Tr0ut-Stream-77", url="bank.example.com")
bank.otp = ("otpauth://totp/River%20Bank:tomas.reyes"
                         "?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=River%20Bank&period=60&digits=8&algorithm=SHA256")

forum = kp.add_entry(root, "Hill Forum", "ada", "hill-forum-pass", url="https://forum.example.net")
forum.set_custom_property("TOTP Seed", "KRUGS4ZANFZSAYJAONSWG4TFOQ", protect=True)
forum.set_custom_property("TOTP Settings", "30;6")

steam = kp.add_entry(root, "Steam", "ada_w", "steam-pass-123")
steam.otp = ("otpauth://totp/Steam:ada_w?secret=MZXW6YTBOI======&issuer=Steam&digits=5&encoder=steam")

kp.add_entry(root, "Library", "ada", "reading-room-4", url="https://library.example.net",
             notes="Card number on the back of the card.")

wiki = kp.add_entry(work, "Team Wiki", "tomas.reyes@example.com", "wiki-wiki-wiki", url="https://wiki.example.com")
wiki.otp = ("otpauth://totp/Team%20Wiki:tomas.reyes?secret=ONSWG4TFORPWC3DQNBQQ====&issuer=Team%20Wiki")

for i in range(extra):
    kp.add_entry(work if i % 3 == 0 else root, f"Site {i + 1:03d}", f"user{i + 1}@example.com", f"filler-password-{i + 1}",
                 url=f"https://site{i + 1}.example.com")

kp.save()
print("wrote", out)
