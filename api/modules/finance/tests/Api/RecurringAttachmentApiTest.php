<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RecurringLinkSource;
use Maggie\Finance\UseCase\AttachRecurringTransactions;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * A transaction and its recurring operation over REST (MAG-305): attached as
 * it is created, attached and detached by hand with a PATCH, refused with a
 * 422 on a settled occurrence, re-categorised with its series.
 */
class RecurringAttachmentApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string FIXTURES = __DIR__.'/../UseCase/fixtures/recurring_attachment.yaml';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures(self::FIXTURES);
    }

    private function login(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    private function iri(string $collection, string $fixture): string
    {
        return '/api/'.$collection.'/'.$this->getFixture($fixture)->getId();
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

    private function reload(string $fixture): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        /** @var Transaction $transaction */
        $transaction = $em->find(Transaction::class, $this->getFixture($fixture)->getId());

        return $transaction;
    }

    private function reloadGym(): RecurringOperation
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        /** @var RecurringOperation $gym */
        $gym = $em->find(RecurringOperation::class, $this->getFixture('gym')->getId());

        return $gym;
    }

    private function catchUp(): void
    {
        self::getContainer()->get(AttachRecurringTransactions::class)->execute($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testANewLineIsAttachedAsItIsCreatedAndBothSidesArePublished(): void
    {
        $this->login();

        $data = $this->send('POST', '/api/transactions', [
            'account' => $this->iri('accounts', 'checking'),
            'amountCents' => -3000,
            'label' => 'PAIEMENT PAR CARTE CLUB FORME 03/05',
            'bookedAt' => '2027-05-03',
        ]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame($this->iri('recurring_operations', 'gym'), $data['recurringOperation']);
        self::assertSame('auto', $data['recurringSource']);
        self::assertSame('series', $data['categorySource']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->getRepository(Transaction::class)->findOneBy(['label' => 'PAIEMENT PAR CARTE CLUB FORME 03/05']);
        self::assertNotNull($stored);
        self::assertSame('2027-05-01', $stored->getRecurringOccurrenceOn()?->format('Y-m-d'));
        self::assertSame('Abonnements', $stored->getCategory()?->getName());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $stored->getId());
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, (string) $this->getFixture('gym')->getId());
    }

    public function testAPatchNamingTheSeriesAttachesByHand(): void
    {
        $this->login();

        $data = $this->send('PATCH', $this->iri('transactions', 'apr_gym_dearer'), [
            'recurringOperation' => $this->iri('recurring_operations', 'gym'),
        ]);

        self::assertResponseIsSuccessful();
        self::assertSame('manual', $data['recurringSource']);

        $stored = $this->reload('apr_gym_dearer');
        self::assertSame('2027-04-01', $stored->getRecurringOccurrenceOn()?->format('Y-m-d'));
        self::assertSame(RecurringLinkSource::Manual, $stored->getRecurringSource());
        self::assertSame(-3990, $this->reloadGym()->getReferenceAmountCents());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, (string) $this->getFixture('gym')->getId());
    }

    public function testAPatchOnASettledOccurrenceIs422NotTheUniqueConstraint(): void
    {
        $this->login();
        $this->catchUp();

        $data = $this->send('PATCH', $this->iri('transactions', 'mar_gym_shop'), [
            'recurringOperation' => $this->iri('recurring_operations', 'gym'),
            'recurringOccurrenceOn' => '2027-02-01',
        ]);

        self::assertResponseStatusCodeSame(422);
        self::assertStringContainsString('already settled', (string) ($data['detail'] ?? $data['hydra:description'] ?? ''));
        self::assertNull($this->reload('mar_gym_shop')->getRecurringOperation());
        self::assertSame('2027-02-01', $this->reload('feb_gym')->getRecurringOccurrenceOn()?->format('Y-m-d'));
    }

    public function testAPatchOnADayThatIsNoDueDateIs422(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('transactions', 'mar_gym_shop'), [
            'recurringOperation' => $this->iri('recurring_operations', 'gym'),
            'recurringOccurrenceOn' => '2027-03-18',
        ]);

        self::assertResponseStatusCodeSame(422);
        self::assertNull($this->reload('mar_gym_shop')->getRecurringOperation());
    }

    public function testAPatchClearingTheSeriesDetachesForGood(): void
    {
        $this->login();
        $this->catchUp();

        $data = $this->send('PATCH', $this->iri('transactions', 'feb_gym'), ['recurringOperation' => null]);

        self::assertResponseIsSuccessful();
        self::assertNull($data['recurringOperation'] ?? null);
        $stored = $this->reload('feb_gym');
        self::assertNull($stored->getRecurringOperation());
        self::assertNull($stored->getRecurringOccurrenceOn());
        self::assertSame(RecurringLinkSource::Manual, $stored->getRecurringSource());

        // The series it left hears of it too.
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, (string) $this->getFixture('gym')->getId());
    }

    public function testAPatchOfAnotherFieldLeavesAnAutomaticAttachmentAutomatic(): void
    {
        $this->login();
        $this->catchUp();

        $this->send('PATCH', $this->iri('transactions', 'feb_gym'), ['isExceptional' => true]);

        self::assertResponseIsSuccessful();
        $stored = $this->reload('feb_gym');
        self::assertNotNull($stored->getRecurringOperation());
        self::assertSame(RecurringLinkSource::Auto, $stored->getRecurringSource());
    }

    public function testANewSeriesCategoryReachesItsLinesExceptThoseCategorisedByHand(): void
    {
        $this->login();
        $this->catchUp();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        /** @var Transaction $byHand */
        $byHand = $this->getFixture('apr_gym_dearer');
        $byHand->assignCategory($this->getFixture('groceries'), CategorySource::Manual);
        self::getContainer()->get(AttachRecurringTransactions::class)->attachByHand($byHand, $this->getFixture('gym'));
        $em->flush();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->send('PATCH', $this->iri('recurring_operations', 'gym'), [
            'category' => $this->iri('categories', 'sport'),
        ]);

        self::assertResponseIsSuccessful();
        $inherited = $this->reload('feb_gym');
        self::assertSame('Sport', $inherited->getCategory()?->getName());
        self::assertSame(CategorySource::Series, $inherited->getCategorySource());
        self::assertSame('Courses', $this->reload('apr_gym_dearer')->getCategory()?->getName());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $inherited->getId());
    }

    public function testDeletingASeriesFreesItsLines(): void
    {
        $this->login();
        $this->catchUp();

        $this->send('DELETE', $this->iri('recurring_operations', 'gym'));

        self::assertResponseStatusCodeSame(204);
        $freed = $this->reload('feb_gym');
        self::assertNull($freed->getRecurringOperation());
        self::assertNull($freed->getRecurringOccurrenceOn());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $freed->getId());
    }
}
