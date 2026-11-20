import pytest

from app.llm.held_secret import HEADER, hold_secrets, mask_owner_secrets
from app.redaction import MASK


@pytest.mark.parametrize("said", ["4821", "4821.", "48 21", "12345678"])
def test_a_message_that_is_only_a_code_is_masked(said):
    assert mask_owner_secrets(said) == MASK


@pytest.mark.parametrize("said", ["123", "123456789"])
def test_three_or_nine_digits_alone_are_kept(said):
    assert mask_owner_secrets(said) == said


@pytest.mark.parametrize(
    ("said", "expected"),
    [
        ("mon code est 4821, rappelez-moi la banque", f"mon code est {MASK}, rappelez-moi la banque"),
        ("mon PIN : 1234", f"mon PIN : {MASK}"),
        ("le CVV c'est 123", f"le CVV c'est {MASK}"),
        ("cryptogramme 456", f"cryptogramme {MASK}"),
        ("digicode 1234B", f"digicode {MASK}"),
        ("mot de passe : Tr0ub4dor&3", f"mot de passe : {MASK}"),
        ("mdp = hunter22", f"mdp = {MASK}"),
        ("mon code est 48 21", f"mon code est {MASK}"),
        ("mon code est 48 21, merci", f"mon code est {MASK}, merci"),
        ("le mot de passe du wifi est maison2024", f"le mot de passe du wifi est {MASK}"),
        ("mon mot de passe Netflix : abc123", f"mon mot de passe Netflix : {MASK}"),
        ("mon mot de passe est abc123, merci", f"mon mot de passe est {MASK}, merci"),
    ],
)
def test_a_labelled_value_is_masked_and_the_rest_kept(said, expected):
    assert mask_owner_secrets(said) == expected


def test_a_card_number_in_a_message_is_masked():
    assert mask_owner_secrets("ma carte 4111 1111 1111 1111") == f"ma carte {MASK}"


@pytest.mark.parametrize(
    "said",
    ["code postal 35000", "le code promo BIENVENUE10", "j'ai 3 rendez-vous demain", "mon mot de passe Google ne marche plus",
     "code du 12 janvier 2025", "mon mot de passe, c'est pénible", "rdv le 12 03 2026 à 14 h",
     "le mot de passe du wifi reste le même", "envoie le code au 06 12 34 56 78"],
)
def test_an_ordinary_message_is_kept(said):
    assert mask_owner_secrets(said) == said


def test_a_held_message_brings_the_consign_before_the_screen():
    said, screen = hold_secrets("4821", None)
    assert said == MASK
    assert screen.startswith(HEADER)

    said, screen = hold_secrets("4821", "[Contexte de l'écran]\nPage")
    assert screen.startswith(HEADER)
    assert screen.endswith("[Contexte de l'écran]\nPage")


def test_nothing_held_leaves_message_and_screen_untouched():
    assert hold_secrets("bonjour", None) == ("bonjour", None)
    assert hold_secrets("bonjour", "écran") == ("bonjour", "écran")
