<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The series the statement implies, offered then accepted (MAG-47).
 * Reading publishes nothing and indexes nothing — N/A for Mercure and
 * Elasticsearch on the GET, which asserts that nothing is written instead.
 */
class RecurringOperationSuggestionsControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function signIn(): void
    {
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/recurring_suggestions.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    /** @return array<string, mixed> */
    private function get(string $query = ''): array
    {
        $this->client->request('GET', '/api/finance/recurring-operations/suggestions'.$query, [], [], $this->authHeaders());

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function accept(mixed $suggestions): array
    {
        $this->client->request(
            'POST',
            '/api/finance/recurring-operations/suggestions/accept',
            [],
            [],
            $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'],
            json_encode(['suggestions' => $suggestions], JSON_THROW_ON_ERROR),
        );

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedGetReturns401(): void
    {
        $this->client->request('GET', '/api/finance/recurring-operations/suggestions');

        self::assertResponseStatusCodeSame(401);
    }

    public function testUnauthenticatedAcceptReturns401(): void
    {
        $this->client->request('POST', '/api/finance/recurring-operations/suggestions/accept', [], [], ['CONTENT_TYPE' => 'application/json'], '{"suggestions":[{}]}');

        self::assertResponseStatusCodeSame(401);
    }

    #[DataProvider('badMinOccurrences')]
    public function testABadMinOccurrencesReturns400(string $value): void
    {
        $this->signIn();

        $this->get('?minOccurrences='.$value);

        self::assertResponseStatusCodeSame(400);
    }

    /** @return iterable<string, array{string}> */
    public static function badMinOccurrences(): iterable
    {
        yield 'text' => ['often'];
        yield 'decimal' => ['2.5'];
        yield 'one' => ['1'];
        yield 'zero' => ['0'];
        yield 'negative' => ['-3'];
        yield 'empty' => [''];
    }

    public function testItListsTheSeriesTheHistoryImplies(): void
    {
        $this->signIn();

        $suggestions = array_column($this->get()['suggestions'], null, 'label');

        self::assertResponseIsSuccessful();
        self::assertSame('monthly', $suggestions['Flixo']['period']);
        self::assertSame('2026-07-12', $suggestions['Flixo']['anchorOn']);
        self::assertSame(-1349, $suggestions['Flixo']['referenceAmountCents']);
        self::assertArrayNotHasKey('SALLE ESCALADE', $suggestions);
    }

    public function testMinOccurrencesNarrowsTheList(): void
    {
        $this->signIn();

        $labels = array_column($this->get('?minOccurrences=4')['suggestions'], 'label');

        self::assertSame(['CLUB FORME'], $labels);
    }

    public function testReadingWritesNothing(): void
    {
        $this->signIn();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $before = $em->getRepository(RecurringOperation::class)->count([]);

        $this->get();

        self::assertSame($before, $em->getRepository(RecurringOperation::class)->count([]));
        self::assertSame(0, $em->getRepository(Transaction::class)->count(['recurringSource' => 'manual']));
    }

    public function testAcceptingCreatesTheSeriesAndAttachesItsHistory(): void
    {
        $this->signIn();
        $flixo = array_column($this->get()['suggestions'], null, 'label')['Flixo'];
        $this->resetMercure();
        $this->resetAsyncTransport();

        $result = $this->accept([$flixo]);

        self::assertResponseIsSuccessful();
        self::assertTrue($result['success']);
        self::assertSame(1, $result['created']);
        self::assertSame(['Flixo'], $result['labels']);
        self::assertSame(3, $result['attached']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $series = $em->getRepository(RecurringOperation::class)->findOneBy(['label' => 'Flixo']);
        self::assertNotNull($series);
        self::assertSame(-1349, $series->getReferenceAmountCents());
        self::assertCount(3, $em->getRepository(Transaction::class)->findBy(['recurringOperation' => $series]));

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    public function testAcceptedPayeeLeavesTheSuggestions(): void
    {
        $this->signIn();
        $flixo = array_column($this->get()['suggestions'], null, 'label')['Flixo'];

        $this->accept([$flixo]);

        self::assertArrayNotHasKey('Flixo', array_column($this->get()['suggestions'], null, 'label'));
    }

    #[DataProvider('unusableBodies')]
    public function testAnUnusableBodyReturns400(mixed $suggestions): void
    {
        $this->signIn();

        $this->accept($suggestions);

        self::assertResponseStatusCodeSame(400);
    }

    /** @return iterable<string, array{mixed}> */
    public static function unusableBodies(): iterable
    {
        yield 'empty list' => [[]];
        yield 'not a list' => ['Flixo'];
        yield 'null' => [null];
        yield 'entry without a category' => [[['label' => 'LOYER SCI VERDA', 'labelPattern' => 'LOYER SCI VERDA']]];
    }

    public function testAnEntryWithoutACategoryCreatesNothing(): void
    {
        $this->signIn();
        $rent = array_column($this->get()['suggestions'], null, 'label')['LOYER SCI VERDA'];

        $this->accept([$rent]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(1, self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(RecurringOperation::class)->count([]));
    }

    public function testAnotherUsersCategoryIsRefused(): void
    {
        $this->signIn();
        $flixo = array_column($this->get()['suggestions'], null, 'label')['Flixo'];

        $this->accept([['categoryId' => (string) $this->getFixture('other_secret')->getId()] + $flixo]);

        self::assertResponseStatusCodeSame(400);
    }
}
