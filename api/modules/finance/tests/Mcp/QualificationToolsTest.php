<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\QualificationKind;
use Maggie\Finance\Mcp\Tool\ManageCategorizationRulesTool;
use Maggie\Finance\Mcp\Tool\ManageRecurringOperationsTool;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Maggie\Finance\UseCase\ApplyCategorizationRules;
use Maggie\Finance\UseCase\AttachRecurringTransactions;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The qualification through Maggie (MAG-476): `mandatory` and `kind` read and
 * written by manage_transactions, manage_recurring_operations and
 * manage_categorization_rules, always together — one without the other is
 * refused with the qualification in place.
 */
class QualificationToolsTest extends KernelTestCase
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
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/qualification.yaml');
        $user = $this->getFixture('test_user');
        self::getContainer()->get(AttachRecurringTransactions::class)->execute($user);
        self::getContainer()->get(ApplyCategorizationRules::class)->execute($user);
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testAQualificationNobodyIsBoundToIsRefused(): void
    {
        $data = $this->call(ManageTransactionsTool::class, 'update', transactionId: $this->id('fuel'), mandatory: false, kind: 'saving');

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertSame(QualificationKind::None, $this->reload(Transaction::class, 'fuel')->getQualification()->getKind());
    }

    public function testATransactionIsQualifiedByHand(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(ManageTransactionsTool::class, 'update', transactionId: $this->id('fuel'), mandatory: false, kind: 'saving');

        self::assertTrue($data['success']);
        self::assertFalse($data['transaction']['mandatory']);
        self::assertSame('saving', $data['transaction']['kind']);
        self::assertSame('manual', $data['transaction']['qualifiedBy']);
        $fuel = $this->reload(Transaction::class, 'fuel');
        self::assertSame(QualificationKind::Saving, $fuel->getQualification()->getKind());
        self::assertSame(CategorySource::Manual, $fuel->getCategorySource());
        self::assertSame('Transport', $fuel->getCategory()?->getName());
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('fuel'));
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('fuel'));
    }

    public function testAListedLineSaysWhoQualifiedIt(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(ManageTransactionsTool::class, 'list');

        $lines = array_column($data['transactions'], null, 'id');
        self::assertTrue($lines[$this->id('jan_loan')]['mandatory']);
        self::assertSame('repayment', $lines[$this->id('jan_loan')]['kind']);
        self::assertSame('series', $lines[$this->id('jan_loan')]['qualifiedBy']);
        self::assertSame($this->id('car_loan'), $lines[$this->id('jan_loan')]['qualifiedBySeriesId']);
        self::assertSame($this->id('rule_fuel'), $lines[$this->id('fuel')]['qualifiedByRuleId']);
    }

    public function testHalfAQualificationIsRefusedWithTheOneInPlace(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(ManageTransactionsTool::class, 'update', transactionId: $this->id('jan_loan'), kind: 'loan');

        self::assertStringContainsString('mandatory and kind go together', $data['error']);
        self::assertSame(['categoryId' => $this->id('loans'), 'mandatory' => true, 'kind' => 'repayment'], $data['currentQualification']);
        self::assertSame(QualificationKind::Repayment, $this->reload(Transaction::class, 'jan_loan')->getQualification()->getKind());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnUnknownKindIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(ManageRecurringOperationsTool::class, 'update', recurringOperationId: $this->id('car_loan'), mandatory: true, kind: 'debt');

        self::assertStringContainsString('kind is one of: none, saving, investment, loan, repayment', $data['error']);
        self::assertSame(QualificationKind::Repayment, $this->reload(RecurringOperation::class, 'car_loan')->getQualification()->getKind());
    }

    public function testASeriesIsCreatedWithItsQualification(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(
            ManageRecurringOperationsTool::class,
            'create',
            label: 'Prêt étudiant',
            categoryId: $this->id('transport'),
            accountId: $this->id('checking'),
            referenceAmountCents: -10000,
            anchorOn: '2027-01-10',
            counterpartyName: 'Banque Études',
            mandatory: true,
            kind: 'loan',
        );

        self::assertTrue($data['recurringOperation']['mandatory']);
        self::assertSame('loan', $data['recurringOperation']['kind']);
        $created = $this->reload(RecurringOperation::class, null, $data['recurringOperation']['id']);
        self::assertSame(QualificationKind::Loan, $created->getQualification()->getKind());
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, $data['recurringOperation']['id']);
    }

    public function testChangingASeriesRequalifiesItsLines(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(ManageRecurringOperationsTool::class, 'update', recurringOperationId: $this->id('car_loan'), mandatory: false, kind: 'loan');

        self::assertSame('loan', $data['recurringOperation']['kind']);
        $line = $this->reload(Transaction::class, 'jan_loan');
        self::assertFalse($line->getQualification()->isMandatory());
        self::assertSame(QualificationKind::Loan, $line->getQualification()->getKind());
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('jan_loan'));
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('jan_loan'));
    }

    public function testChangingARuleRequalifiesItsLinesButNotOneQualifiedByHand(): void
    {
        $this->loginFixtureUser();
        $this->call(ManageTransactionsTool::class, 'update', transactionId: $this->id('fuel'), mandatory: true, kind: 'none');
        $this->resetMercure();

        $data = $this->call(ManageCategorizationRulesTool::class, 'update', categorizationRuleId: $this->id('rule_fuel'), mandatory: false, kind: 'investment');

        self::assertSame('investment', $data['rule']['kind']);
        self::assertSame(QualificationKind::Investment, $this->reload(CategorizationRule::class, 'rule_fuel')->getQualification()->getKind());
        self::assertSame(QualificationKind::None, $this->reload(Transaction::class, 'fuel')->getQualification()->getKind());
        $this->assertNothingPublishedOn('/transactions/'.$this->id('fuel'));
    }

    public function testARuleChangeReachesTheLinesItFiled(): void
    {
        $this->loginFixtureUser();

        $this->call(ManageCategorizationRulesTool::class, 'update', categorizationRuleId: $this->id('rule_fuel'), mandatory: false, kind: 'investment');

        $fuel = $this->reload(Transaction::class, 'fuel');
        self::assertSame(QualificationKind::Investment, $fuel->getQualification()->getKind());
        self::assertSame('rule', $fuel->getQualifiedBy());
        $this->assertMercureUpdatePublished('/categorization_rules/');
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('fuel'));
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('fuel'));
    }

    public function testHalfARuleQualificationIsRefusedWithTheOneInPlace(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(ManageCategorizationRulesTool::class, 'update', categorizationRuleId: $this->id('rule_fuel'), mandatory: false);

        self::assertSame(['categoryId' => $this->id('transport'), 'mandatory' => true, 'kind' => 'none'], $data['currentQualification']);
        self::assertTrue($this->reload(CategorizationRule::class, 'rule_fuel')->getQualification()->isMandatory());
    }

    /**
     * @param class-string $tool
     *
     * @return array<string, mixed>
     */
    private function call(string $tool, string $action, mixed ...$arguments): array
    {
        $instance = self::getContainer()->get($tool);

        return json_decode($instance($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    /**
     * @template T of object
     *
     * @param class-string<T> $class
     *
     * @return T
     */
    private function reload(string $class, ?string $fixture, ?string $id = null): object
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        /** @var T $entity */
        $entity = $em->find($class, $id ?? $this->id((string) $fixture));

        return $entity;
    }
}
