<?php

namespace Maggie\Core\Tests\Elasticsearch;

use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Uid\Ulid;

#[Indexed(index: 'test_entities', module: 'test')]
class StubIndexedEntity
{
    private Ulid $id;

    #[IndexedField(type: 'text', boost: 3.0)]
    private string $title;

    #[IndexedField(type: 'keyword')]
    private string $status;

    #[IndexedField(type: 'date')]
    private \DateTimeImmutable $createdAt;

    #[IndexedField(type: 'date', format: 'yyyy-MM-dd')]
    private \DateTimeImmutable $day;

    #[IndexedField(type: 'text', keyword: true)]
    private string $location;

    #[IndexedRelation(targetEntity: 'App\Entity\User', sourceField: 'userId')]
    private object $user;

    private string $notIndexed;
}

class StubNotIndexedEntity
{
    private string $name;
}

class IndexMetadataReaderTest extends TestCase
{
    private IndexMetadataReader $reader;

    protected function setUp(): void
    {
        $this->reader = new IndexMetadataReader();
    }

    public function testReadReturnsNullForNonIndexedEntity(): void
    {
        $result = $this->reader->read(StubNotIndexedEntity::class);
        self::assertNull($result);
    }

    public function testReadReturnsIndexMetadata(): void
    {
        $result = $this->reader->read(StubIndexedEntity::class);

        self::assertNotNull($result);
        self::assertSame('test_entities', $result['index']);
        self::assertSame('test', $result['module']);
    }

    public function testReadParsesFieldAttributes(): void
    {
        $result = $this->reader->read(StubIndexedEntity::class);
        $fields = $result['fields'];

        self::assertArrayHasKey('title', $fields);
        self::assertSame('text', $fields['title']['type']);
        self::assertArrayNotHasKey('boost', $fields['title']); // boost is query-time only

        // Boost stored separately
        self::assertArrayHasKey('title', $result['boosts']);
        self::assertSame(3.0, $result['boosts']['title']);

        self::assertArrayHasKey('status', $fields);
        self::assertSame('keyword', $fields['status']['type']);

        self::assertArrayHasKey('createdAt', $fields);
        self::assertSame('date', $fields['createdAt']['type']);
    }

    /**
     * A day-typed field declares its format, and the mapping carries it
     * (MAG-251).
     *
     * Without one, Elasticsearch reads the field with its default formats,
     * which accept a plain day *and* an instant — so a field that is only ever
     * a day takes instants too, and a `range` over days silently compares the
     * two shapes.
     */
    public function testADayFieldCarriesItsFormat(): void
    {
        $fields = $this->reader->read(StubIndexedEntity::class)['fields'];

        self::assertSame(['type' => 'date', 'format' => 'yyyy-MM-dd'], $fields['day']);
        // And a date field that declares none stays as it was.
        self::assertArrayNotHasKey('format', $fields['createdAt']);
    }

    public function testReadParsesKeywordSubField(): void
    {
        $result = $this->reader->read(StubIndexedEntity::class);
        $fields = $result['fields'];

        self::assertArrayHasKey('location', $fields);
        self::assertSame('text', $fields['location']['type']);
        self::assertArrayHasKey('fields', $fields['location']);
        self::assertSame('keyword', $fields['location']['fields']['keyword']['type']);
    }

    public function testReadParsesRelations(): void
    {
        $result = $this->reader->read(StubIndexedEntity::class);
        $relations = $result['relations'];

        self::assertArrayHasKey('user', $relations);
        self::assertSame('App\Entity\User', $relations['user']['targetEntity']);
        self::assertSame('userId', $relations['user']['sourceField']);
    }

    public function testNonIndexedFieldsAreExcluded(): void
    {
        $result = $this->reader->read(StubIndexedEntity::class);
        $fields = $result['fields'];

        self::assertArrayNotHasKey('notIndexed', $fields);
        self::assertArrayNotHasKey('id', $fields);
    }

    public function testGetIndexNameReturnsName(): void
    {
        self::assertSame('test_entities', $this->reader->getIndexName(StubIndexedEntity::class));
    }

    public function testGetIndexNameReturnsNullForNonIndexed(): void
    {
        self::assertNull($this->reader->getIndexName(StubNotIndexedEntity::class));
    }

    public function testIndicesOfFollowsTheAncestors(): void
    {
        self::assertSame(['meals', 'events'], $this->reader->indicesOf(\Maggie\Cookbook\Entity\Meal::class));
        self::assertSame(['products'], $this->reader->indicesOf(\Maggie\Cookbook\Entity\Ingredient::class));
        self::assertSame([], $this->reader->indicesOf(StubNotIndexedEntity::class));
    }

    /** MAG-382: an event's instants name the day fields that stand for them on an all-day event. */
    public function testTheDayFieldsOfTheInstantsAreRead(): void
    {
        $meta = $this->reader->read(\Maggie\Calendar\Entity\Event::class);

        self::assertSame([
            'startAt' => ['field' => 'startDate', 'exclusiveEnd' => false],
            'endAt' => ['field' => 'endDate', 'exclusiveEnd' => true],
        ], $meta['dayFields'] ?? null);
        self::assertSame(['type' => 'date', 'format' => 'yyyy-MM-dd'], $meta['fields']['startDate'] ?? null);
        self::assertArrayNotHasKey('dayField', $meta['fields']['startAt']);
    }
}
