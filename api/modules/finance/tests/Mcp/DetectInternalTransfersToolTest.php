<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Mcp\Tool\DetectInternalTransfersTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The tool has no action and no required argument: it pairs the history of the
 * bound user, or says it has none.
 *
 * *Written exemption:* `N/A — unknown action and missing required argument`.
 */
class DetectInternalTransfersToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function detect(?int $limitDays = null, ?bool $dryRun = null): array
    {
        $tool = self::getContainer()->get(DetectInternalTransfersTool::class);

        return json_decode($tool($limitDays, $dryRun), true, 512, JSON_THROW_ON_ERROR);
    }

    private function tx(string $ref): Transaction
    {
        /** @var Transaction $transaction */
        $transaction = $this->getFixture($ref);

        return $transaction;
    }

    public function testWithoutAUserBoundItReportsTheError(): void
    {
        $this->loadFixtures('internal_transfers.yaml');

        self::assertArrayHasKey('error', $this->detect());
    }

    public function testItPairsTheHistoryAndTellsEveryScreen(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->tx('transfer_out');
        $in = $this->tx('transfer_in');

        $data = $this->detect();

        self::assertTrue($data['success']);
        self::assertSame(1, $data['matched']);
        self::assertSame((string) $out->getId(), $data['pairs'][0]['transactionId']);
        self::assertSame((string) $in->getId(), $data['pairs'][0]['counterpartId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(TransferKind::Internal, $em->find(Transaction::class, $out->getId())->getTransferKind());
        self::assertSame(TransferKind::Internal, $em->find(Transaction::class, $in->getId())->getTransferKind());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testADryRunReportsThePairWithoutWritingIt(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->tx('transfer_out');

        $data = $this->detect(dryRun: true);

        self::assertTrue($data['dryRun']);
        self::assertSame(1, $data['matched']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(TransferKind::None, $em->find(Transaction::class, $out->getId())->getTransferKind());
        $this->assertMercureUpdateCount(0);
    }

    public function testANegativeLimitDaysIsRefused(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->detect(limitDays: -30));
    }

    public function testSomeoneElsesHistoryIsNeverPaired(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser('other_user');

        $data = $this->detect();

        self::assertSame(0, $data['matched']);
        self::assertSame(1, $data['scanned'], 'the other user has one movement and nothing to pair it with');
    }
}
