"""MAG-886: the code chooses how a proaction's message reaches the owner — `decide` is pure."""

from datetime import UTC, datetime, timedelta

from app.proactions.triage import Delivery, decide
from app.proactions.verdict import Verdict, normalize_topic, read_verdict

NOW = datetime(2030, 11, 13, 10, 0, tzinfo=UTC)
RESPONSE = "Votre enveloppe Loisirs est à 72 %, monsieur."


def _verdict(impact="low", evidence="solid", topic="enveloppe-loisirs", changed=None, deadline=None) -> Verdict:
    return Verdict(impact=impact, evidence=evidence, topic=topic, changed=changed, deadline=deadline)


def _told(topic="enveloppe-loisirs", ago=timedelta(days=1)) -> list[dict]:
    return [{"topic": topic, "at": (NOW - ago).isoformat(), "delivery": "thread", "notice": "72 %"}]


def test_reminder_low_verdict_interrupts():
    assert decide("reminder", RESPONSE, _verdict(impact="low", evidence="weak"), [], NOW) is Delivery.INTERRUPT


def test_reminder_signaled_topic_interrupts():
    assert decide("reminder", RESPONSE, _verdict(), _told(), NOW) is Delivery.INTERRUPT


def test_check_without_verdict_interrupts():
    assert decide("check", RESPONSE, None, [], NOW) is Delivery.INTERRUPT


def test_check_low_goes_to_thread():
    assert decide("check", RESPONSE, _verdict(impact="low"), [], NOW) is Delivery.THREAD


def test_check_medium_weak_goes_to_thread():
    assert decide("check", RESPONSE, _verdict(impact="medium", evidence="weak"), [], NOW) is Delivery.THREAD


def test_check_medium_solid_interrupts():
    assert decide("check", RESPONSE, _verdict(impact="medium", evidence="solid"), [], NOW) is Delivery.INTERRUPT


def test_check_medium_weak_deadline_soon_interrupts():
    verdict = _verdict(impact="medium", evidence="weak", deadline=NOW + timedelta(hours=20))
    assert decide("check", RESPONSE, verdict, [], NOW) is Delivery.INTERRUPT


def test_check_medium_weak_deadline_far_goes_to_thread():
    verdict = _verdict(impact="medium", evidence="weak", deadline=NOW + timedelta(days=3))
    assert decide("check", RESPONSE, verdict, [], NOW) is Delivery.THREAD


def test_check_high_interrupts():
    assert decide("check", RESPONSE, _verdict(impact="high", evidence="weak"), [], NOW) is Delivery.INTERRUPT


def test_signaled_topic_unchanged_is_none():
    assert decide("check", RESPONSE, _verdict(impact="high"), _told(), NOW) is Delivery.NONE


def test_signaled_topic_changed_weak_is_none():
    verdict = _verdict(impact="high", evidence="weak", changed="Passée de 60 % à 72 %.")
    assert decide("check", RESPONSE, verdict, _told(), NOW) is Delivery.NONE


def test_signaled_topic_changed_solid_follows_impact():
    changed = "Passée de 60 % à 72 %."
    assert decide("check", RESPONSE, _verdict(impact="low", changed=changed), _told(), NOW) is Delivery.THREAD
    assert decide("check", RESPONSE, _verdict(impact="high", changed=changed), _told(), NOW) is Delivery.INTERRUPT


def test_signaled_topic_older_than_window_not_deduplicated():
    assert decide("check", RESPONSE, _verdict(), _told(ago=timedelta(days=8)), NOW) is Delivery.THREAD


def test_another_topic_is_not_deduplicated():
    assert decide("check", RESPONSE, _verdict(), _told(topic="facture-edf"), NOW) is Delivery.THREAD


def test_empty_response_is_none():
    assert decide("reminder", "  \n", None, [], NOW) is Delivery.NONE
    assert decide("check", "", _verdict(impact="high"), [], NOW) is Delivery.NONE


def test_topic_normalized():
    assert normalize_topic("Enveloppe Loisirs !") == "enveloppe-loisirs"
    assert normalize_topic("Échéance — EDF:novembre") == "echeance-edf:novembre"
    assert normalize_topic("x" * 100) == "x" * 80
    assert normalize_topic("  ") is None
    assert normalize_topic(None) is None


def test_the_last_verdict_is_read_and_an_incomplete_one_is_none():
    calls = [
        {"name": "proaction_verdict", "input": {"impact": "high", "evidence": "solid", "topic": "a"}},
        {"name": "get_envelopes", "input": {}},
        {
            "name": "proaction_verdict",
            "input": {
                "impact": "low",
                "evidence": "weak",
                "topic": "Enveloppe Loisirs",
                "changed": " ",
                "deadline": "2030-11-14T08:00:00",
                "headline": "Loisirs à 72 %.",
            },
        },
    ]

    verdict = read_verdict(calls)

    assert (verdict.impact, verdict.evidence, verdict.topic) == ("low", "weak", "enveloppe-loisirs")
    assert verdict.changed is None
    assert verdict.deadline is not None and verdict.deadline.tzinfo is not None
    assert verdict.headline == "Loisirs à 72 %."
    assert read_verdict([{"name": "proaction_verdict", "input": {"impact": "urgent", "evidence": "solid", "topic": "a"}}]) is None
    assert read_verdict([{"name": "proaction_verdict", "input": {"impact": "low", "evidence": "solid"}}]) is None
    assert read_verdict([]) is None


def test_an_unreadable_deadline_is_no_deadline():
    verdict = read_verdict(
        [{"name": "proaction_verdict", "input": {"impact": "low", "evidence": "solid", "topic": "a", "deadline": "demain"}}]
    )

    assert verdict is not None and verdict.deadline is None
