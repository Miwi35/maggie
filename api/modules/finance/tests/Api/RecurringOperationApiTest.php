<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\ReferenceAmountSource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The recurring operations over REST: the series alone, its occurrences
 * computed and never stored.
 */
class RecurringOperationApiTest extends WebTestCase
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
        $this->loadFixtures('recurring_operation.yaml');
    }

    private function login(): void
    {
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
    }

    private function iri(string $collection, string $fixture): string
    {
        return '/api/'.$collection.'/'.$this->getFixture($fixture)->getId();
    }

    /** @param array<string, mixed> $body */
    private function send(string $method, string $uri, array $body = [], bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request($method, $uri, [], [], $authenticated ? array_merge($headers, $this->authHeaders()) : $headers, [] === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));
    }

    /**
     * Flixo, 13,49 € on the 12th of every month.
     *
     * @param array<string, mixed> $overrides
     *
     * @return array<string, mixed>
     */
    private function flixo(array $overrides = []): array
    {
        return array_merge([
            'label' => 'Abonnement Flixo',
            'counterpartyName' => 'Flixo',
            'category' => $this->iri('categories', 'subscriptions'),
            'account' => $this->iri('accounts', 'checking'),
            'period' => 'monthly',
            'anchorOn' => '2027-01-12',
            'referenceAmountCents' => -1349,
        ], $overrides);
    }

    private function rows(): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(RecurringOperation::class)->count([]);
    }

    private function reload(string $fixture): ?RecurringOperation
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(RecurringOperation::class, $this->getFixture($fixture)->getId());
    }

    /** @return array<string, mixed> */
    private function json(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testCreateRequiresAuthentication(): void
    {
        $this->send('POST', '/api/recurring_operations', $this->flixo(), authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreatePersistsPublishesAndIndexes(): void
    {
        $this->login();
        $before = $this->rows();

        $this->send('POST', '/api/recurring_operations', $this->flixo());

        self::assertResponseStatusCodeSame(201);
        $data = $this->json();
        self::assertSame('Abonnement Flixo', $data['label']);
        self::assertSame(-1349, $data['monthlyCostCents']);
        self::assertSame(-16188, $data['yearlyCostCents']);
        self::assertSame('flixo', $data['counterpartyKey']);

        self::assertSame($before + 1, $this->rows());
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $stored = $em->getRepository(RecurringOperation::class)->findOneBy(['label' => 'Abonnement Flixo']);
        self::assertNotNull($stored);
        self::assertSame(-1349, $stored->getReferenceAmountCents());
        self::assertSame('2027-01-12', $stored->getAnchorOn()->format('Y-m-d'));
        self::assertSame(RecurrencePeriod::Monthly, $stored->getPeriod());
        self::assertSame(ReferenceAmountSource::Measured, $stored->getReferenceSource());
        self::assertSame(20, $stored->getAmountTolerancePercent());
        self::assertSame(5, $stored->getDateToleranceDays());
        self::assertTrue($stored->getUser()->getId()->equals($this->getFixture('test_user')->getId()));

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    public function testNoOccurrenceIsWrittenDown(): void
    {
        $this->login();

        $this->send('POST', '/api/recurring_operations', $this->flixo());
        self::assertResponseStatusCodeSame(201);

        $connection = self::getContainer()->get('doctrine.orm.entity_manager')->getConnection();
        $tables = $connection->createSchemaManager()->listTableNames();
        self::assertSame([], array_values(array_filter($tables, static fn (string $t) => str_contains($t, 'occurrence'))));
        self::assertSame(3, (int) $connection->fetchOne('SELECT COUNT(*) FROM recurring_operation'), 'the two fixtures and the new series, nothing more');
    }

    /** @return iterable<string, array{array<string, mixed>, string}> */
    public static function refusedBodies(): iterable
    {
        yield 'zero reference amount' => [['referenceAmountCents' => 0], 'referenceAmountCents'];
        yield 'amount tolerance above 100 %' => [['amountTolerancePercent' => 101], 'amountTolerancePercent'];
        yield 'negative amount tolerance' => [['amountTolerancePercent' => -1], 'amountTolerancePercent'];
        yield 'date tolerance above a month' => [['dateToleranceDays' => 32], 'dateToleranceDays'];
        yield 'end before the anchor' => [['endsOn' => '2027-01-11'], 'endsOn'];
        yield 'nothing to recognise it by' => [['counterpartyName' => null], 'counterpartyName'];
        yield 'no label' => [['label' => ''], 'label'];
    }

    /**
     * @param array<string, mixed> $overrides
     */
    #[\PHPUnit\Framework\Attributes\DataProvider('refusedBodies')]
    public function testAnInvalidSeriesIsRefused(array $overrides, string $field): void
    {
        $this->login();
        $before = $this->rows();

        $this->send('POST', '/api/recurring_operations', $this->flixo($overrides));

        self::assertResponseStatusCodeSame(422);
        self::assertContains($field, array_column($this->json()['violations'] ?? [], 'propertyPath'));
        self::assertSame($before, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnIncomeCategoryOnAnExpenseIsRefused(): void
    {
        $this->login();
        $before = $this->rows();

        $this->send('POST', '/api/recurring_operations', $this->flixo(['category' => $this->iri('categories', 'salary')]));

        self::assertResponseStatusCodeSame(422);
        self::assertSame($before, $this->rows());
    }

    /**
     * Another user's object is refused like a missing one (400, as everywhere
     * in Finance: ForeignReferenceApiTest), and nothing is stored.
     *
     * @return iterable<string, array{string, string, string}>
     */
    public static function foreignReferences(): iterable
    {
        yield 'category' => ['category', 'categories', 'other_secret'];
        yield 'account' => ['account', 'accounts', 'other_checking'];
    }

    #[\PHPUnit\Framework\Attributes\DataProvider('foreignReferences')]
    public function testAnotherUsersReferenceIsRefused(string $field, string $collection, string $fixture): void
    {
        $this->login();
        $before = $this->rows();

        $this->send('POST', '/api/recurring_operations', $this->flixo([$field => $this->iri($collection, $fixture)]));

        self::assertResponseStatusCodeSame(400);
        self::assertSame($before, $this->rows());
        self::assertStringNotContainsString('Compte secret', (string) $this->client->getResponse()->getContent());
        self::assertStringNotContainsString('Cadeaux secrets', (string) $this->client->getResponse()->getContent());
        $this->assertMercureUpdateCount(0);
    }

    public function testGetReturnsTheSeriesWithItsCosts(): void
    {
        $this->login();

        $this->send('GET', $this->iri('recurring_operations', 'gym'));

        self::assertResponseIsSuccessful();
        $data = $this->json();
        self::assertSame('Salle de sport', $data['label']);
        self::assertSame(-3000, $data['monthlyCostCents']);
        self::assertSame(-36000, $data['yearlyCostCents']);
    }

    public function testAnotherUsersSeriesIsNotFound(): void
    {
        $this->login();

        $this->send('GET', $this->iri('recurring_operations', 'other_rent'));
        self::assertResponseStatusCodeSame(404);

        $this->send('PATCH', $this->iri('recurring_operations', 'other_rent'), ['referenceAmountCents' => -1]);
        self::assertResponseStatusCodeSame(404);

        $this->send('DELETE', $this->iri('recurring_operations', 'other_rent'));
        self::assertResponseStatusCodeSame(404);

        self::assertSame(-90000, $this->reload('other_rent')?->getReferenceAmountCents());
        $this->assertMercureUpdateCount(0);
    }

    public function testTheCollectionListsOnlyTheUsersOwnSeries(): void
    {
        $this->login();

        $this->send('GET', '/api/recurring_operations');

        self::assertResponseIsSuccessful();
        self::assertSame(['Salle de sport'], array_column($this->json()['member'], 'label'));
    }

    public function testPatchRequiresAuthentication(): void
    {
        $this->send('PATCH', $this->iri('recurring_operations', 'gym'), ['referenceAmountCents' => -3500], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchChangesOnlyWhatIsSentPublishesAndIndexes(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('recurring_operations', 'gym'), [
            'referenceAmountCents' => -3500,
            'referenceSource' => 'declared',
            'dayRule' => 'last_day_of_month',
        ]);

        self::assertResponseIsSuccessful();
        $refreshed = $this->reload('gym');
        self::assertSame(-3500, $refreshed?->getReferenceAmountCents());
        self::assertSame(ReferenceAmountSource::Declared, $refreshed->getReferenceSource());
        self::assertSame('Club Forme', $refreshed->getCounterpartyName());
        self::assertSame('2027-12-01', $refreshed->getEndsOn()?->format('Y-m-d'));

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    public function testPatchWithANullEndLetsTheSeriesRun(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('recurring_operations', 'gym'), ['endsOn' => null]);

        self::assertResponseIsSuccessful();
        self::assertNull($this->reload('gym')?->getEndsOn());
    }

    public function testPatchWithAnEndBeforeTheAnchorIsRefused(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('recurring_operations', 'gym'), ['endsOn' => '2026-12-31']);

        self::assertResponseStatusCodeSame(422);
        self::assertSame('2027-12-01', $this->reload('gym')?->getEndsOn()?->format('Y-m-d'));
    }

    public function testDeleteRemovesPublishesAndUnindexes(): void
    {
        $this->login();
        $this->send('DELETE', $this->iri('recurring_operations', 'gym'));

        self::assertResponseStatusCodeSame(204);
        self::assertNull($this->reload('gym'));

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchDeleteDispatched('recurring_operations');
    }
}
