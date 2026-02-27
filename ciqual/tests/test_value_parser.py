from app.import_ciqual import parse_value


def test_empty_string():
    assert parse_value("") is None


def test_dash():
    assert parse_value("-") is None


def test_traces():
    assert parse_value("traces") == 0.0


def test_traces_uppercase():
    assert parse_value("Traces") == 0.0


def test_less_than():
    assert parse_value("< 0.5") == 0.25


def test_less_than_no_space():
    assert parse_value("<0.5") == 0.25


def test_french_decimal():
    assert parse_value("12,5") == 12.5


def test_integer():
    assert parse_value("100") == 100.0


def test_float():
    assert parse_value("3.14") == 3.14


def test_whitespace():
    assert parse_value("  42  ") == 42.0


def test_less_than_with_comma():
    assert parse_value("< 0,1") == 0.05
