<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** `manage_transactions` actions `settle_rejection` and `unsettle_rejection` (MAG-424). */
final class RejectionSettlementToolTest extends KernelTestCase
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

    public function testWithoutAUserBothActionsAnswerWithAnError(): void
    {
        $this->loadFixtures('rejection_settlement.yaml');

        foreach (['settle_rejection', 'unsettle_rejection'] as $action) {
            self::assertArrayHasKey('error', $this->call($action, transactionId: $this->id('edf_debit')));
        }
        self::assertNull($this->stored('edf_debit')->getRejectionSettledAt());
    }

    public function testALineThatIsNotARejectionIsRefused(): void
    {
        $this->loadFixtures('rejection_settlement.yaml');
        $this->loginFixtureUser();

        $data = $this->call('settle_rejection', transactionId: $this->id('groceries'));

        self::assertStringContainsString('not a rejected payment', $data['error']);
        self::assertNull($this->stored('groceries')->getRejectionSettledAt());
        self::assertArrayHasKey('error', $this->call('settle_rejection'));
        self::assertArrayHasKey('error', $this->call('settle_rejection', transactionId: $this->id('stranger_rejection')));
        self::assertNull($this->stored('stranger_rejection')->getRejectionSettledAt());
    }

    public function testSettleRecordsItOnBothLegsAndPublishes(): void
    {
        $this->loadFixtures('rejection_settlement.yaml');
        $this->loginFixtureUser();

        $data = $this->call('settle_rejection', transactionId: $this->id('edf_credit'));

        self::assertTrue($data['success']);
        self::assertNotNull($data['transaction']['rejectionSettledAt']);
        self::assertNotNull($this->stored('edf_debit')->getRejectionSettledAt());
        self::assertNotNull($this->stored('edf_credit')->getRejectionSettledAt());
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('edf_debit'));
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testUnsettlePutsItBack(): void
    {
        $this->loadFixtures('rejection_settlement.yaml');
        $this->loginFixtureUser();
        $this->call('settle_rejection', transactionId: $this->id('edf_debit'));
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->call('unsettle_rejection', transactionId: $this->id('edf_debit'));

        self::assertTrue($data['success']);
        self::assertNull($data['transaction']['rejectionSettledAt']);
        self::assertNull($this->stored('edf_debit')->getRejectionSettledAt());
        self::assertNull($this->stored('edf_credit')->getRejectionSettledAt());
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('edf_credit'));
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return array<string, mixed> */
    private function call(mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        return json_decode($tool(...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function stored(string $ref): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Transaction::class, $this->getFixture($ref)->getId());
    }
}
