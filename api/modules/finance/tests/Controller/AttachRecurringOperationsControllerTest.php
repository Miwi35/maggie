<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RecurringLinkSource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The catch-up pass of the recurring operations over REST (MAG-305). */
class AttachRecurringOperationsControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string ENDPOINT = '/api/finance/recurring-operations/attach';
    private const string FIXTURES = __DIR__.'/../UseCase/fixtures/recurring_attachment.yaml';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function attach(array $body = []): array
    {
        $this->client->request(
            'POST',
            self::ENDPOINT,
            [],
            [],
            $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'],
            [] === $body ? '' : json_encode($body, JSON_THROW_ON_ERROR),
        );

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function login(): void
    {
        $this->loadFixtures(self::FIXTURES);
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    private function reload(string $fixture): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        /** @var Transaction $transaction */
        $transaction = $em->find(Transaction::class, $this->getFixture($fixture)->getId());

        return $transaction;
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', self::ENDPOINT);

        self::assertResponseStatusCodeSame(401);
    }

    public function testANonIntegerLimitDaysReturns400(): void
    {
        $this->login();

        $this->attach(['limitDays' => 'trois mois']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testANegativeLimitDaysReturns400(): void
    {
        $this->login();

        $this->attach(['limitDays' => -30]);

        self::assertResponseStatusCodeSame(400);
    }

    public function testAStringDryRunReturns400(): void
    {
        $this->login();

        $this->attach(['dryRun' => 'false']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testAttachesPublishesAndIndexesTheLineAndItsSeries(): void
    {
        $this->login();
        // A measured reference the attachment moves: the series changes too.
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $gym = $em->find(RecurringOperation::class, $this->getFixture('gym')->getId());
        self::assertNotNull($gym);
        $gym->setReferenceAmountCents(-3100);
        $em->flush();

        $data = $this->attach();

        self::assertResponseIsSuccessful();
        self::assertTrue($data['success']);
        self::assertFalse($data['dryRun']);
        self::assertSame(1, $data['attached']);
        self::assertSame((string) $this->getFixture('feb_gym')->getId(), $data['attachments'][0]['transactionId']);
        self::assertSame('2027-02-01', $data['attachments'][0]['occurrenceOn']);

        // The price rise is proposed, never written; the shop purchase is a one-off.
        self::assertSame(1, $data['proposed']);
        self::assertSame('amount_up', $data['proposals'][0]['reason']);
        self::assertSame(33, $data['proposals'][0]['amountChangePercent']);
        self::assertSame('Salle de sport', $data['proposals'][0]['recurringOperationLabel']);

        $stored = $this->reload('feb_gym');
        self::assertSame((string) $this->getFixture('gym')->getId(), (string) $stored->getRecurringOperation()?->getId());
        self::assertSame(RecurringLinkSource::Auto, $stored->getRecurringSource());
        self::assertSame(CategorySource::Series, $stored->getCategorySource());
        self::assertNull($this->reload('apr_gym_dearer')->getRecurringOperation());
        self::assertNull($this->reload('mar_gym_shop')->getRecurringOperation());
        self::assertSame(-3000, self::getContainer()->get('doctrine.orm.entity_manager')->find(RecurringOperation::class, $this->getFixture('gym')->getId())?->getReferenceAmountCents());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $stored->getId());
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, (string) $this->getFixture('gym')->getId());
    }

    public function testADryRunWritesNothing(): void
    {
        $this->login();

        $data = $this->attach(['dryRun' => true]);

        self::assertResponseIsSuccessful();
        self::assertTrue($data['dryRun']);
        self::assertSame(1, $data['attached']);
        self::assertNull($this->reload('feb_gym')->getRecurringOperation());
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Transaction::class);
    }

    public function testLimitDaysLeavesOlderLinesAlone(): void
    {
        $this->login();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->getFixture('feb_gym')->setBookedAt(new \DateTimeImmutable('-400 days'));
        $em->flush();

        $data = $this->attach(['limitDays' => 30]);

        self::assertResponseIsSuccessful();
        self::assertSame(0, $data['attached']);
        self::assertNull($this->reload('feb_gym')->getRecurringOperation());
    }

    public function testAnotherUsersSeriesNeverClaimsTheLines(): void
    {
        $this->login();

        $this->attach();

        self::assertNotSame(
            (string) $this->getFixture('other_gym')->getId(),
            (string) $this->reload('feb_gym')->getRecurringOperation()?->getId(),
        );
    }
}
