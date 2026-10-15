<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\QualificationKind;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\MessageHandler\UpdateOperationHandler;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * `/api/operations` (MAG-478): the one endpoint of the operations, with or
 * without recurrence, and the two views the clients still read —
 * `/api/recurring_operations` the series, `/api/categorization_rules` the
 * others — all serving one entity at one IRI.
 */
class OperationApiTest extends WebTestCase
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
        $this->loadFixtures(__DIR__.'/fixtures/operations.yaml');
    }

    public function testAnonymousCallsAreRefused(): void
    {
        $this->send('GET', '/api/operations', authenticated: false);
        self::assertResponseStatusCodeSame(401);

        $this->send('POST', '/api/operations', ['labelPattern' => 'UGC', 'category' => $this->iri('categories', 'food')], authenticated: false);
        self::assertResponseStatusCodeSame(401);
        self::assertSame(3, $this->operations());
    }

    public function testTheListHoldsEveryOperationOfTheUserAtItsOneIri(): void
    {
        $this->login();

        $data = $this->send('GET', '/api/operations');

        self::assertResponseIsSuccessful();
        self::assertEqualsCanonicalizing(
            [$this->iri('recurring_operations', 'gym'), $this->iri('recurring_operations', 'rule_carrefour')],
            array_column($data['member'], '@id'),
        );
    }

    public function testTheListIsNarrowedToTheSeriesOrToTheOthers(): void
    {
        $this->login();

        self::assertSame(['Salle de sport'], array_column($this->send('GET', '/api/operations?exists[period]=true')['member'], 'label'));
        self::assertSame(['CARREFOUR'], array_column($this->send('GET', '/api/operations?exists[period]=false')['member'], 'labelPattern'));
    }

    public function testTheOldViewsListWhatTheyListedBefore(): void
    {
        $this->login();

        self::assertSame([$this->iri('recurring_operations', 'gym')], array_column($this->send('GET', '/api/recurring_operations')['member'], '@id'));
        self::assertSame([$this->iri('recurring_operations', 'rule_carrefour')], array_column($this->send('GET', '/api/categorization_rules')['member'], '@id'));
        // A client cannot widen a view back to the whole list.
        self::assertSame([$this->iri('recurring_operations', 'gym')], array_column($this->send('GET', '/api/recurring_operations?exists[period]=false')['member'], '@id'));
    }

    public function testAnOperationWithoutRecurrenceIsCreatedPublishedAndIndexed(): void
    {
        $this->login();

        $data = $this->send('POST', '/api/operations', [
            'labelPattern' => 'LIDL',
            'direction' => 'debit',
            'priority' => 5,
            'category' => $this->iri('categories', 'food'),
            'qualification' => ['mandatory' => true, 'kind' => 'none'],
        ]);

        self::assertResponseStatusCodeSame(201);
        self::assertNull($data['period'] ?? null);
        $stored = $this->reload($this->idOf($data));
        self::assertFalse($stored->hasRecurrence());
        self::assertSame('LIDL', $stored->getLabelPattern());
        self::assertSame(AmountDirection::Debit, $stored->getDirection());
        self::assertSame(5, $stored->getPriority());
        self::assertTrue($stored->getQualification()->isMandatory());
        self::assertNull($stored->getAccount());
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, $this->idOf($data));
    }

    public function testASeriesIsCreatedThroughTheSameEndpoint(): void
    {
        $this->login();

        $data = $this->send('POST', '/api/operations', [
            'label' => 'Flixo',
            'counterpartyName' => 'FLIXO SAS',
            'category' => $this->iri('categories', 'subscriptions'),
            'account' => $this->iri('accounts', 'checking'),
            'period' => 'monthly',
            'anchorOn' => '2027-01-12',
            'referenceAmountCents' => -1349,
            'qualification' => ['mandatory' => false, 'kind' => 'none'],
        ]);

        self::assertResponseStatusCodeSame(201);
        $stored = $this->reload($this->idOf($data));
        self::assertTrue($stored->hasRecurrence());
        self::assertSame(RecurrencePeriod::Monthly, $stored->getPeriod());
        self::assertSame(-1349, $stored->getReferenceAmountCents());
        self::assertSame(-1349 * 12, $data['yearlyCostCents']);
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, $this->idOf($data));
    }

    public function testAPartOfARecurrenceIsRefused(): void
    {
        $this->login();

        $data = $this->send('POST', '/api/operations', [
            'labelPattern' => 'FLIXO',
            'category' => $this->iri('categories', 'subscriptions'),
            'anchorOn' => '2027-01-12',
            'referenceAmountCents' => -1349,
        ]);

        self::assertResponseStatusCodeSame(422);
        self::assertContains(RecurringOperation::PARTIAL_RECURRENCE, array_column($data['violations'], 'message'));
        self::assertSame(3, $this->operations());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnOperationWithNothingToRecogniseByIsRefused(): void
    {
        $this->login();

        $data = $this->send('POST', '/api/operations', ['category' => $this->iri('categories', 'food')]);

        self::assertResponseStatusCodeSame(422);
        self::assertContains(RecurringOperation::UNRECOGNISABLE, array_column($data['violations'], 'message'));
        self::assertSame(3, $this->operations());
    }

    public function testAnOperationWithoutRecurrenceIsChangedAtItsIriAsItWas(): void
    {
        $this->login();

        // Where the rules screens send it: the IRI every view gives.
        $data = $this->send('PATCH', $this->iri('recurring_operations', 'rule_carrefour'), ['priority' => 3, 'isActive' => false]);

        self::assertResponseIsSuccessful();
        self::assertSame(3, $data['priority']);
        $stored = $this->reload((string) $this->getFixture('rule_carrefour')->getId());
        self::assertSame(3, $stored->getPriority());
        self::assertFalse($stored->isActive());
        self::assertFalse($stored->hasRecurrence());
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, (string) $stored->getId());
    }

    public function testAnOperationKeepsItsRecurrenceOrItsLackOfOne(): void
    {
        $this->login();

        $data = $this->send('PATCH', '/api/operations/'.$this->getFixture('rule_carrefour')->getId(), [
            'period' => 'monthly',
            'anchorOn' => '2027-01-03',
            'referenceAmountCents' => -4599,
            'account' => $this->iri('accounts', 'checking'),
            'label' => 'Courses',
        ]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString(UpdateOperationHandler::RECURRENCE_KEPT, (string) json_encode($data));
        self::assertFalse($this->reload((string) $this->getFixture('rule_carrefour')->getId())->hasRecurrence());
    }

    public function testAQualificationChangeReachesTheLinesTheOperationFiled(): void
    {
        $this->login();
        $this->send('POST', '/api/finance/operations/apply');
        self::assertResponseIsSuccessful();

        $this->send('PATCH', '/api/operations/'.$this->getFixture('rule_carrefour')->getId(), ['qualification' => ['mandatory' => true, 'kind' => 'saving']]);

        self::assertResponseIsSuccessful();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $line = $em->find(\Maggie\Finance\Entity\Transaction::class, $this->getFixture('carrefour_line')->getId());
        self::assertSame('rule', $line?->getQualifiedBy());
        self::assertTrue($line->getQualification()->isMandatory());
        self::assertSame(QualificationKind::Saving, $line->getQualification()->getKind());
    }

    public function testAnotherUsersOperationIsNotFound(): void
    {
        $this->login();

        $this->send('GET', '/api/operations/'.$this->getFixture('other_rule')->getId());
        self::assertResponseStatusCodeSame(404);

        $this->send('DELETE', '/api/operations/'.$this->getFixture('other_rule')->getId());
        self::assertResponseStatusCodeSame(404);
        self::assertNotNull($this->reload((string) $this->getFixture('other_rule')->getId()));
    }

    public function testAnOperationIsDeletedPublishedAndUnindexed(): void
    {
        $this->login();
        $id = (string) $this->getFixture('rule_carrefour')->getId();

        $this->send('DELETE', '/api/operations/'.$id);

        self::assertResponseStatusCodeSame(204);
        self::assertNull($this->reload($id));
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchDeleteDispatched('recurring_operations');
    }

    private function login(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    private function iri(string $collection, string $fixture): string
    {
        return '/api/'.$collection.'/'.$this->getFixture($fixture)->getId();
    }

    /** @param array<string, mixed> $data */
    private function idOf(array $data): string
    {
        return basename((string) $data['@id']);
    }

    private function operations(): int
    {
        return \count(self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(RecurringOperation::class)->findAll());
    }

    private function reload(string $id): ?RecurringOperation
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(RecurringOperation::class, $id);
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function send(string $method, string $uri, array $body = [], bool $authenticated = true): array
    {
        $headers = [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request($method, $uri, [], [], $authenticated ? array_merge($headers, $this->authHeaders()) : $headers, [] === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));

        $content = (string) $this->client->getResponse()->getContent();

        return '' === $content ? [] : json_decode($content, true, 512, JSON_THROW_ON_ERROR);
    }
}
