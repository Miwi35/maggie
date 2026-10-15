<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Mcp\Tool\ManageEnvelopesTool;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * One-off envelopes through manage_envelopes, and the hand attribution of a
 * spend to one through manage_transactions (MAG-414).
 */
class OneOffEnvelopeToolsTest extends KernelTestCase
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
        $this->loadFixtures('one_off_envelope.yaml');
    }

    /** @return array<string, mixed> */
    private function envelopes(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageEnvelopesTool::class);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function transactions(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function reloadTransaction(string $fixture): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Transaction::class, $this->getFixture($fixture)->getId());
    }

    public function testSettingAOneOffEnvelopeNobodyIsBoundToIsRefused(): void
    {
        $data = $this->envelopes('set', mode: 'one_off', name: 'Vacances', amountCents: 150000);

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        $this->assertMercureUpdateCount(0);
    }

    public function testSetCreatesAOneOffEnvelope(): void
    {
        $this->loginFixtureUser();

        $data = $this->envelopes('set', mode: 'one_off', name: 'Vacances', amountCents: 150000);

        self::assertTrue($data['success']);
        self::assertSame('one_off', $data['envelope']['mode']);
        self::assertSame('Vacances', $data['envelope']['name']);
        self::assertNull($data['envelope']['categoryId']);
        self::assertNull($data['envelope']['year']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $created = $em->find(Envelope::class, $data['envelope']['id']);
        self::assertSame(BudgetMode::OneOff, $created->getMode());
        self::assertSame(150000, $created->getAmountCents());
        self::assertNull($created->getCategory());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testAOneOffEnvelopeWithoutNameIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->envelopes('set', mode: 'one_off', amountCents: 150000);

        self::assertStringContainsString('name', $data['error']);
        $this->assertMercureUpdateCount(0);
    }

    public function testAOneOffEnvelopeWithACategoryIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->envelopes('set', mode: 'one_off', name: 'Vacances', amountCents: 150000, categoryId: $this->id('leisure'));

        self::assertArrayHasKey('error', $data);
        $this->assertMercureUpdateCount(0);
    }

    public function testAMonthlyEnvelopeWithoutCategoryIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->envelopes('set', mode: 'monthly', amountCents: 15000, year: 2026, month: 11);

        self::assertStringContainsString('categoryId', $data['error']);
        $this->assertMercureUpdateCount(0);
    }

    public function testUpdateRenamesAOneOffEnvelope(): void
    {
        $this->loginFixtureUser();

        $data = $this->envelopes('update', envelopeId: $this->id('hellfest'), name: 'Hellfest 2027');

        self::assertSame('Hellfest 2027', $data['envelope']['name']);
        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testUpdateCannotTurnAOneOffEnvelopeMonthly(): void
    {
        $this->loginFixtureUser();

        $data = $this->envelopes('update', envelopeId: $this->id('hellfest'), mode: 'monthly', categoryId: $this->id('leisure'), year: 2026, month: 10);

        self::assertStringContainsString('cannot change', $data['error']);
        $this->assertMercureUpdateCount(0);
    }

    public function testListCarriesTheOneOffEnvelopes(): void
    {
        $this->loginFixtureUser();

        $names = array_column($this->envelopes('list')['envelopes'], 'name');

        self::assertContains('Hellfest 2026', $names);
        self::assertNotContains('Fête secrète', $names);
    }

    public function testStatusReportsTheOneOffAndAnnualEnvelopesApart(): void
    {
        $this->loginFixtureUser();

        $data = $this->envelopes('status', year: 2026, month: 10);

        self::assertSame(30000, $data['totalBudgetedCents']);
        self::assertSame(['Voyages'], array_column($data['outsideMonthlyBudget']['annual'], 'categoryName'));
        $hellfest = $data['outsideMonthlyBudget']['oneOff'][0];
        self::assertSame('Hellfest 2026', $hellfest['name']);
        self::assertSame(3000, $hellfest['plannedCents']);
    }

    public function testAttributingASpendNobodyIsBoundToIsRefused(): void
    {
        $data = $this->transactions('update', transactionId: $this->id('ticket'), oneOffEnvelopeId: $this->id('hellfest'));

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertNull($this->reloadTransaction('ticket')->getOneOffEnvelope());
    }

    public function testAttributeASpendThenTakeItBack(): void
    {
        $this->loginFixtureUser();

        $data = $this->transactions('update', transactionId: $this->id('ticket'), oneOffEnvelopeId: $this->id('hellfest'));

        self::assertSame($this->id('hellfest'), $data['transaction']['oneOffEnvelopeId']);
        self::assertSame($this->id('leisure'), $data['transaction']['categoryId']);
        self::assertSame($this->id('hellfest'), (string) $this->reloadTransaction('ticket')->getOneOffEnvelope()?->getId());
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);

        $status = $this->envelopes('status', year: 2026, month: 10);
        self::assertSame(2500, $status['budgets'][0]['consumedCents']);
        self::assertSame(8900, $status['outsideMonthlyBudget']['oneOff'][0]['consumedCents']);

        $this->resetMercure();
        $this->resetAsyncTransport();
        $data = $this->transactions('update', transactionId: $this->id('ticket'), oneOffEnvelopeId: '');

        self::assertNull($data['transaction']['oneOffEnvelopeId']);
        self::assertNull($this->reloadTransaction('ticket')->getOneOffEnvelope());
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);

        $status = $this->envelopes('status', year: 2026, month: 10);
        self::assertSame(11400, $status['budgets'][0]['consumedCents']);
    }

    public function testClearTakesTheAttributionBack(): void
    {
        $this->loginFixtureUser();

        $data = $this->transactions('update', transactionId: $this->id('tent'), clear: ['oneOffEnvelope']);

        self::assertNull($data['transaction']['oneOffEnvelopeId']);
        self::assertNull($this->reloadTransaction('tent')->getOneOffEnvelope());
    }

    public function testAttributingToAMonthlyEnvelopeIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->transactions('update', transactionId: $this->id('ticket'), oneOffEnvelopeId: $this->id('leisure_october'));

        self::assertStringContainsString('one-off envelope only', $data['error']);
        self::assertNull($this->reloadTransaction('ticket')->getOneOffEnvelope());
        $this->assertMercureUpdateCount(0);
    }

    public function testAttributingToAnotherUsersEnvelopeIsReportedAsUnknown(): void
    {
        $this->loginFixtureUser();

        $data = $this->transactions('update', transactionId: $this->id('ticket'), oneOffEnvelopeId: $this->id('other_hellfest'));

        self::assertStringContainsString('Envelope not found', $data['error']);
        self::assertNull($this->reloadTransaction('ticket')->getOneOffEnvelope());
        $this->assertMercureUpdateCount(0);
    }

    public function testANameOverTheLimitIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->envelopes('set', mode: 'one_off', name: str_repeat('a', 121), amountCents: 1000);

        self::assertStringContainsString('120 characters', $data['error']);
        $this->assertMercureUpdateCount(0);
    }
}
