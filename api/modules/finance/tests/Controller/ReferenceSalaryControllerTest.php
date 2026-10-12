<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\RecurringLinkSource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The reference salary over REST (MAG-273): a pure read, no body, no parameter — one happy path per source. */
class ReferenceSalaryControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private const string ENDPOINT = '/api/finance/reference-salary';
    private const string FIXTURES = __DIR__.'/../UseCase/fixtures/reference_salary.yaml';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    /** @return array<string, mixed> */
    private function read(string $as): array
    {
        $this->authenticateAsUser($this->getFixture($as));
        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT);

        self::assertResponseStatusCodeSame(401);
    }

    public function testTheExpectedSalary(): void
    {
        $this->loadFixtures(self::FIXTURES);

        $data = $this->read('test_user');

        self::assertSame(495000, $data['monthlyCents']);
        self::assertSame('expected', $data['source']);
        self::assertTrue($data['isConfigured']);
        self::assertSame(['Salaire ACME'], array_column($data['operations'], 'label'));
    }

    public function testTheMeasuredSalary(): void
    {
        $this->loadFixtures(self::FIXTURES);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        /** @var RecurringOperation $series */
        $series = $this->getFixture('measured_op');
        for ($monthsAgo = 12; $monthsAgo >= 1; --$monthsAgo) {
            $on = new \DateTimeImmutable(sprintf('first day of this month -%d months +24 days', $monthsAgo));
            $payment = new Transaction();
            $payment->setUser($this->getFixture('measured_user'));
            $payment->setAccount($this->getFixture('measured_checking'));
            $payment->setAmountCents(495000);
            $payment->setBookedAt($on);
            $payment->setLabel('VIR GLOBEX');
            $payment->attachToRecurring($series, $on, RecurringLinkSource::Manual);
            $em->persist($payment);
        }
        $this->flushWithoutTransactionEffects($em);

        $data = $this->read('measured_user');

        self::assertSame(495000, $data['monthlyCents']);
        self::assertSame('measured', $data['source']);
        self::assertSame(12, $data['operations'][0]['paymentCount']);
    }

    public function testTheDeclaredSalary(): void
    {
        $this->loadFixtures(self::FIXTURES);

        $data = $this->read('declared_user');

        self::assertSame(520000, $data['monthlyCents']);
        self::assertSame('declared', $data['source']);
    }

    public function testNothingKnownIsUnknownNotZero(): void
    {
        $this->loadFixtures(self::FIXTURES);

        $data = $this->read('empty_user');

        self::assertNull($data['monthlyCents']);
        self::assertSame('unknown', $data['source']);
        self::assertFalse($data['isConfigured']);
    }
}
