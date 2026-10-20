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
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Mcp\Tool\ManagePeriodPlanTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** Maggie writes the plan of a period one element at a time (MAG-462). */
class ManagePeriodPlanToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    private const FIXTURE = __DIR__.'/../Controller/fixtures/period_plan.yaml';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function signIn(): void
    {
        $this->loadFixtures(self::FIXTURE);
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $action, ?string $period = '2026-Q2', ?string $category = null, ?int $amountCents = null, ?string $name = null, ?string $label = null, ?string $month = null, ?int $from = null, ?int $to = null, ?int $seasonAmount = null, ?string $currency = null, ?string $account = null): array
    {
        /** @var ManagePeriodPlanTool $tool */
        $tool = self::getContainer()->get(ManagePeriodPlanTool::class);

        return json_decode($tool(
            action: $action,
            period: $period,
            categoryId: null === $category ? null : (string) $this->getFixture($category)->getId(),
            amountCents: $amountCents,
            currency: $currency,
            name: $name,
            label: $label,
            month: $month,
            accountId: null === $account ? null : (string) $this->getFixture($account)->getId(),
            seasonFromMonth: $from,
            seasonToMonth: $to,
            seasonAmountCents: $seasonAmount,
        ), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserTheToolReportsAnError(): void
    {
        $this->loadFixtures(self::FIXTURE);

        $result = $this->call('set_monthly_envelope', category: 'food', amountCents: 100);

        self::assertSame(MissingMcpUserException::MESSAGE, $result['error']);
    }

    public function testAnUnknownActionIsRefused(): void
    {
        $this->signIn();

        $result = $this->call('delete_everything');

        self::assertStringContainsString('Unknown action', $result['error']);
    }

    public function testThePeriodIsRequiredAndMustBeOne(): void
    {
        $this->signIn();

        self::assertStringContainsString('period is required', $this->call('set_monthly_envelope', period: null, category: 'food', amountCents: 100)['error']);
        self::assertStringContainsString('period must be', $this->call('set_monthly_envelope', period: '2026-04', category: 'food', amountCents: 100)['error']);
    }

    public function testAMissingParameterIsReportedAndNothingIsWritten(): void
    {
        $this->signIn();

        self::assertStringContainsString('categoryId', $this->call('set_monthly_envelope', amountCents: 100)['error']);
        self::assertStringContainsString('amountCents', $this->call('set_monthly_envelope', category: 'food')['error']);
        self::assertStringContainsString('name', $this->call('set_one_off_envelope', amountCents: 100)['error']);
        self::assertStringContainsString('label', $this->call('set_event', category: 'leisure', amountCents: 100, month: '2026-05')['error']);
        self::assertStringContainsString('month', $this->call('set_event', category: 'leisure', amountCents: 100, label: 'Yoga')['error']);
        self::assertStringContainsString('together', $this->call('set_monthly_envelope', category: 'food', amountCents: 100, from: 6)['error']);
        $this->assertMercureUpdateCount(0);
    }

    public function testAMonthlyEnvelopeIsSetOnEachMonthOfThePeriod(): void
    {
        $this->signIn();

        $result = $this->call('set_monthly_envelope', category: 'leisure', amountCents: 25000, from: 6, to: 6, seasonAmount: 40000);

        self::assertTrue($result['success']);
        self::assertSame('2026-Q2', $result['period']['key']);
        self::assertSame('monthly', $result['applied']['kind']);
        self::assertSame(3, $result['applied']['created']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $amounts = [];
        foreach ([4, 5, 6] as $month) {
            $envelope = $em->getRepository(Envelope::class)->findOneBy([
                'category' => $this->getFixture('leisure')->getId(),
                'mode' => BudgetMode::Monthly,
                'year' => 2026,
                'month' => $month,
            ]);
            $amounts[$month] = $envelope?->getMonthAmountCents();
        }
        self::assertSame([4 => 25000, 5 => 25000, 6 => 40000], $amounts);
        $this->assertMercureUpdatePublished('/api/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testAnAnnualEnvelopeNeedsAYear(): void
    {
        $this->signIn();

        $refused = $this->call('set_annual_envelope', category: 'leisure', amountCents: 240000);
        self::assertStringContainsString('calendar year', $refused['error']);

        $result = $this->call('set_annual_envelope', period: '2027', category: 'leisure', amountCents: 240000, currency: 'USD');

        self::assertSame(1, $result['applied']['created']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $annual = $em->getRepository(Envelope::class)->findOneBy(['mode' => BudgetMode::Annual, 'year' => 2027]);
        self::assertSame(240000, $annual->getAmountCents());
        self::assertSame('USD', $annual->getCurrency());
    }

    public function testAOneOffEnvelopeIsFoundByItsNameWhenTheCallIsRepeated(): void
    {
        $this->signIn();

        $this->call('set_one_off_envelope', name: 'Hellfest 2026', amountCents: 60000);
        $again = $this->call('set_one_off_envelope', name: 'hellfest 2026', amountCents: 70000);

        self::assertSame(1, $again['applied']['updated']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $found = $em->getRepository(Envelope::class)->findBy(['mode' => BudgetMode::OneOff]);
        self::assertCount(1, $found);
        self::assertSame(70000, $found[0]->getAmountCents());
        self::assertNull($found[0]->getCategory());
    }

    public function testAnExpenseIsPlannedInAMonthOfThePeriod(): void
    {
        $this->signIn();

        $result = $this->call('set_event', category: 'leisure', label: 'Kiné', amountCents: 9000, month: '2026-06');

        self::assertSame(1, $result['applied']['created']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $planned = $em->getRepository(Transaction::class)->find($result['applied']['ids'][0]);
        self::assertSame(-9000, $planned->getAmountCents());
        self::assertSame(TransactionStatus::Planned, $planned->getStatus());
        self::assertSame('2026-06-15', $planned->getBookedAt()->format('Y-m-d'));
        $this->assertMercureUpdatePublished('/api/transactions/');
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $result['applied']['ids'][0]);

        $outside = $this->call('set_event', category: 'leisure', label: 'Kiné', amountCents: 9000, month: '2026-07');
        self::assertStringContainsString('outside the period', $outside['error']);
    }

    public function testAnExpenseOnAnAccountOfSomeoneElseIsRefused(): void
    {
        $this->signIn();

        $result = $this->call('set_event', category: 'leisure', label: 'Kiné', amountCents: 9000, month: '2026-06', account: 'other_checking');

        self::assertStringContainsString('Account not found', $result['error']);
        self::assertSame(0, \count(self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(Transaction::class)->findBy(['label' => 'Kiné'])));
    }

    public function testACategoryOfSomeoneElseIsRefusedWithoutWritingAnything(): void
    {
        $this->signIn();

        $result = $this->call('set_monthly_envelope', category: 'other_leisure', amountCents: 100);

        self::assertStringContainsString('Category not found', $result['error']);
        $this->assertMercureUpdateCount(0);
    }
}
