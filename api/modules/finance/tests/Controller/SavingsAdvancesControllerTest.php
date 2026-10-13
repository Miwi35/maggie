<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Tests\UseCase\SavingsAdvancesWorld;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The savings advances over REST (MAG-276): a pure read, no parameter. It
 * writes nothing, so it publishes and indexes nothing either.
 */
class SavingsAdvancesControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use SavingsAdvancesWorld;

    private const string ENDPOINT = '/api/finance/savings-advances';

    private KernelBrowser $client;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT);

        self::assertResponseStatusCodeSame(401);
    }

    public function testItGivesEachSourceAndTheDelay(): void
    {
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/savings_advances.yaml');
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
        [$advance] = $this->transfer('livret', 'checking', 150000, 'today -5 days', TransferKind::Advance);
        $this->repay($advance, 'livret', 50000, 'today');
        $this->transfer('other_livret', 'other_checking', 90000, 'today -5 days', TransferKind::Advance);
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(1, $data['advances']);
        self::assertSame(50000, $data['advances'][0]['repaidCents']);
        self::assertSame(100000, $data['totalOutstandingCents']);
        self::assertSame(['Livret A'], array_column($data['bySource'], 'sourceAccountName'));
        self::assertTrue($data['salaryDelay']['isConfigured']);
        self::assertTrue($data['salaryDelay']['isLate']);
    }
}
