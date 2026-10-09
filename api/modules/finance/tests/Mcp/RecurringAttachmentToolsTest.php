<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\RecurringLinkSource;
use Maggie\Finance\Mcp\Tool\ManageRecurringOperationsTool;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Maggie\Finance\UseCase\AttachRecurringTransactions;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * Attaching a transaction to a recurring operation through Maggie (MAG-305):
 * `attach` / `detach` on manage_recurring_operations, `recurringOperationId`
 * on manage_transactions. Every gesture is the user's own: `manual`.
 */
class RecurringAttachmentToolsTest extends KernelTestCase
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
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/recurring_attachment.yaml');
    }

    /** @return array<string, mixed> */
    private function series(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageRecurringOperationsTool::class);

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

    private function reload(string $fixture): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        /** @var Transaction $transaction */
        $transaction = $em->find(Transaction::class, $this->getFixture($fixture)->getId());

        return $transaction;
    }

    public function testAnAttachNobodyIsBoundToIsRefused(): void
    {
        $data = $this->series('attach', recurringOperationId: $this->id('gym'), transactionId: $this->id('apr_gym_dearer'));

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertNull($this->reload('apr_gym_dearer')->getRecurringOperation());
    }

    public function testAnUpdateNobodyIsBoundToIsRefused(): void
    {
        $data = $this->transactions('update', transactionId: $this->id('apr_gym_dearer'), recurringOperationId: $this->id('gym'));

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertNull($this->reload('apr_gym_dearer')->getRecurringOperation());
    }

    public function testAttachRequiresTheSeriesAndTheLine(): void
    {
        $this->loginFixtureUser();

        $data = $this->series('attach', recurringOperationId: $this->id('gym'));

        self::assertStringContainsString('transactionId', $data['error']);
    }

    public function testAnotherUsersSeriesIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->series('attach', recurringOperationId: $this->id('other_gym'), transactionId: $this->id('apr_gym_dearer'));

        self::assertStringContainsString('Recurring operation not found', $data['error']);
        self::assertNull($this->reload('apr_gym_dearer')->getRecurringOperation());
    }

    public function testAttachConfirmsAProposalByHandAndPublishesBothSides(): void
    {
        $this->loginFixtureUser();

        $data = $this->series('attach', recurringOperationId: $this->id('gym'), transactionId: $this->id('apr_gym_dearer'));

        self::assertTrue($data['success']);
        self::assertSame('manual', $data['transaction']['recurringSource']);
        self::assertSame('2027-04-01', $data['transaction']['recurringOccurrenceOn']);
        self::assertSame(-3990, $data['recurringOperation']['referenceAmountCents']);

        $stored = $this->reload('apr_gym_dearer');
        self::assertSame($this->id('gym'), (string) $stored->getRecurringOperation()?->getId());
        self::assertSame(RecurringLinkSource::Manual, $stored->getRecurringSource());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('apr_gym_dearer'));
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, $this->id('gym'));
    }

    public function testAttachToASettledOccurrenceIsRefused(): void
    {
        $user = $this->loginFixtureUser();
        self::getContainer()->get(AttachRecurringTransactions::class)->execute($user);

        $data = $this->series('attach', recurringOperationId: $this->id('gym'), transactionId: $this->id('mar_gym_shop'), recurringOccurrenceOn: '2027-02-01');

        self::assertStringContainsString('already settled', $data['error']);
        self::assertNull($this->reload('mar_gym_shop')->getRecurringOperation());
    }

    public function testDetachTakesTheLineOutForGood(): void
    {
        $user = $this->loginFixtureUser();
        self::getContainer()->get(AttachRecurringTransactions::class)->execute($user);

        $data = $this->series('detach', transactionId: $this->id('feb_gym'));

        self::assertTrue($data['success']);
        self::assertNull($data['transaction']['recurringOperationId']);
        $stored = $this->reload('feb_gym');
        self::assertNull($stored->getRecurringOperation());
        self::assertSame(RecurringLinkSource::Manual, $stored->getRecurringSource());
    }

    public function testManageTransactionsAttachesAndDetachesByHand(): void
    {
        $this->loginFixtureUser();

        $attached = $this->transactions('update', transactionId: $this->id('mar_gym_shop'), recurringOperationId: $this->id('gym'), recurringOccurrenceOn: '2027-03-01');

        self::assertTrue($attached['success']);
        self::assertSame($this->id('gym'), $attached['transaction']['recurringOperationId']);
        self::assertSame('2027-03-01', $attached['transaction']['recurringOccurrenceOn']);
        self::assertSame('manual', $attached['transaction']['recurringSource']);
        self::assertSame('series', $attached['transaction']['categorySource']);

        $detached = $this->transactions('update', transactionId: $this->id('mar_gym_shop'), clear: ['recurringOperation']);

        self::assertTrue($detached['success']);
        self::assertNull($detached['transaction']['recurringOperationId']);
        $stored = $this->reload('mar_gym_shop');
        self::assertNull($stored->getRecurringOperation());
        self::assertSame(RecurringLinkSource::Manual, $stored->getRecurringSource());
    }

    public function testALineMarkedAsATransferLeavesItsSeries(): void
    {
        $user = $this->loginFixtureUser();
        self::getContainer()->get(AttachRecurringTransactions::class)->execute($user);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->transactions('update', transactionId: $this->id('feb_gym'), transferKind: 'internal');

        self::assertTrue($data['success']);
        self::assertNull($data['transaction']['recurringOperationId']);
        $stored = $this->reload('feb_gym');
        self::assertNull($stored->getRecurringOperation());
        self::assertNull($stored->getRecurringOccurrenceOn());
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, $this->id('gym'));
    }
}
