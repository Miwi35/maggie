from app.common.frontmatter import parse_frontmatter


class TestParseFrontmatter:
    def test_splits_mapping_and_stripped_body(self):
        assert parse_frontmatter("---\nname: a\ntags: [x]\n---\n\nBody\n") == ({"name": "a", "tags": ["x"]}, "Body")

    def test_body_may_contain_a_rule(self):
        _, body = parse_frontmatter("---\nname: a\n---\nOne\n---\nTwo\n")
        assert body == "One\n---\nTwo"

    def test_no_frontmatter(self):
        assert parse_frontmatter("just text") is None

    def test_unterminated_frontmatter(self):
        assert parse_frontmatter("---\nname: a\n") is None

    def test_invalid_yaml(self):
        assert parse_frontmatter("---\nnot: valid: yaml: [[\n---\nBody") is None

    def test_frontmatter_that_is_not_a_mapping(self):
        assert parse_frontmatter("---\n- a\n- b\n---\nBody") is None
