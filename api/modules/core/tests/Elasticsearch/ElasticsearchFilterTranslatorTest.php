<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use Maggie\Core\Elasticsearch\Query\ElasticsearchFilterTranslator;
use PHPUnit\Framework\TestCase;

/**
 * The translator turns what a client sent into what Elasticsearch can answer.
 *
 * Both of the things it can get wrong are silent. A clause it fails to build
 * leaves the query unfiltered (04f1de6). A clause naming a field the index
 * does not hold matches nothing, so the client gets an empty list — and a
 * sort on an analysed text field throws, after which the provider serves the
 * collection from Doctrine and only a log line says so.
 */
final class ElasticsearchFilterTranslatorTest extends TestCase
{
    private ElasticsearchFilterTranslator $translator;

    protected function setUp(): void
    {
        $this->translator = new ElasticsearchFilterTranslator();
    }

    public function testASortOnATextFieldUsesItsKeywordSubField(): void
    {
        $translated = $this->translator->translate(
            self::query('order[name]=asc'),
            ['name' => ['type' => 'text', 'fields' => ['keyword' => ['type' => 'keyword']]]],
        );

        self::assertSame([['name.keyword' => 'asc']], $translated['sort']);
    }

    public function testASortOnADateFieldIsLeftAlone(): void
    {
        $translated = $this->translator->translate(
            self::query('order[startAt]=desc'),
            ['startAt' => ['type' => 'date']],
        );

        self::assertSame([['startAt' => 'desc']], $translated['sort']);
    }

    public function testASortOnAKeywordFieldIsLeftAlone(): void
    {
        $translated = $this->translator->translate(
            self::query('order[criticality]=asc'),
            ['criticality' => ['type' => 'keyword']],
        );

        self::assertSame([['criticality' => 'asc']], $translated['sort']);
    }

    /**
     * No keyword sub-field means no sortable form, and rewriting to one that
     * does not exist would replace a loud failure with a silent empty sort.
     * Leaving it alone keeps the exception, which the provider turns into a
     * Doctrine fallback — and QueryParameterContractTest fails before any
     * client can rely on it.
     */
    public function testASortOnAnAnalysedTextFieldWithNoKeywordSubFieldIsLeftAlone(): void
    {
        $translated = $this->translator->translate(
            self::query('order[summary]=asc'),
            ['summary' => ['type' => 'text']],
        );

        self::assertSame([['summary' => 'asc']], $translated['sort']);
    }

    public function testASortOnAnUnmappedFieldIsLeftAlone(): void
    {
        $translated = $this->translator->translate(self::query('order[name]=asc'), []);

        self::assertSame([['name' => 'asc']], $translated['sort']);
    }

    /**
     * The relation case. API Platform names the filter after the property;
     * IndexManager flattens the relation to its source field. A term query on
     * the property name matches nothing and raises nothing.
     */
    public function testARelationFilterQueriesTheIndexedSourceField(): void
    {
        $translated = $this->translator->translate(
            self::query('account=01ARZ3NDEKTSV4RRFFQ69G5FAV'),
            [],
            ['account' => ['targetEntity' => 'Account', 'sourceField' => 'accountId']],
        );

        self::assertSame([['term' => ['accountId' => '01ARZ3NDEKTSV4RRFFQ69G5FAV']]], $translated['filter']);
    }

    /**
     * And the clients send the IRI, because that is what the provider handed
     * them (c359b43). The index holds the identifier.
     */
    public function testARelationFilterAcceptsTheIriTheClientsWereGiven(): void
    {
        $translated = $this->translator->translate(
            self::query('account=' . rawurlencode('/api/accounts/01ARZ3NDEKTSV4RRFFQ69G5FAV')),
            [],
            ['account' => ['targetEntity' => 'Account', 'sourceField' => 'accountId']],
        );

        self::assertSame([['term' => ['accountId' => '01ARZ3NDEKTSV4RRFFQ69G5FAV']]], $translated['filter']);
    }

    public function testANonRelationTermFilterKeepsItsName(): void
    {
        $translated = $this->translator->translate(self::query('priority=high'));

        self::assertSame([['term' => ['priority' => 'high']]], $translated['filter']);
    }

    /**
     * 04f1de6: PHP parses `exists[completedAt]=false` into a nested array,
     * and the translator used to walk past it, leaving the query unfiltered.
     */
    public function testExistsFalseBecomesAMustNotClause(): void
    {
        $translated = $this->translator->translate(self::query('exists[completedAt]=false'));

        self::assertSame(
            [['bool' => ['must_not' => ['exists' => ['field' => 'completedAt']]]]],
            $translated['filter'],
        );
    }

    public function testExistsTrueBecomesAnExistsClause(): void
    {
        $translated = $this->translator->translate(self::query('exists[rrule]=true'));

        self::assertSame([['exists' => ['field' => 'rrule']]], $translated['filter']);
    }

    public function testDateOperatorsBecomeRangeClauses(): void
    {
        $translated = $this->translator->translate(
            self::query('dueDate[before]=2026-04-01T00%3A00%3A00%2B00%3A00'),
        );

        self::assertSame(
            [['range' => ['dueDate' => ['lte' => '2026-04-01T00:00:00+00:00']]]],
            $translated['filter'],
        );
    }

    public function testPaginationIsNotTranslated(): void
    {
        $translated = $this->translator->translate(self::query('page=2&itemsPerPage=50'));

        self::assertSame([], $translated['filter']);
        self::assertSame([], $translated['sort']);
        self::assertSame([], $translated['must']);
    }

    /**
     * Through parse_str, so the test sees the nested-array shape PHP hands
     * the application rather than one written by hand to match the code.
     *
     * @return array<string, mixed>
     */
    private static function query(string $queryString): array
    {
        parse_str($queryString, $parsed);

        return $parsed;
    }
}
