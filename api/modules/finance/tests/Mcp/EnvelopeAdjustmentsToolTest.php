<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Mcp\Tool\EnvelopeAdjustmentsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class EnvelopeAdjustmentsToolTest extends KernelTestCase
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

    private function world(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/envelope_adjustments.yaml');
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function tool(): EnvelopeAdjustmentsTool
    {
        return self::getContainer()->get(EnvelopeAdjustmentsTool::class);
    }

    /** @return array<string, mixed> */
    private function call(string $action, ?string $month = '2026-09', ?string $categoryIds = null): array
    {
        return json_decode($this->tool()($action, $month, $categoryIds), true, 512, JSON_THROW_ON_ERROR);
    }

    private function octoberEnvelope(string $category): ?Envelope
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Envelope::class)->findOneBy([
            'category' => $this->getFixture($category)->getId(),
            'mode' => BudgetMode::Monthly,
            'year' => 2026,
            'month' => 10,
        ]);
    }

    public function testWithoutAUserTheToolReportsAnError(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/envelope_adjustments.yaml');

        self::assertArrayHasKey('error', $this->call('suggest'));
    }

    public function testABadMonthOrActionIsAnError(): void
    {
        $this->world();

        self::assertArrayHasKey('error', $this->call('suggest', '2026-13'));
        self::assertArrayHasKey('error', $this->call('wander'));
    }

    public function testSuggestListsOnlyTheUnfitEnvelopesWithTheirReason(): void
    {
        $this->world();

        $result = $this->call('suggest');
        $suggested = [];
        foreach ($result['envelopes'] as $line) {
            if (null !== $line['suggestion']) {
                $suggested[$line['categoryName']] = $line['suggestion'];
            }
        }

        self::assertSame(3, $result['suggestionCount']);
        self::assertSame(25000, $suggested['Loisirs']['proposedCents']);
        self::assertSame('raise', $suggested['Loisirs']['direction']);
        self::assertNotSame('', $suggested['Loisirs']['reason']);
        self::assertArrayNotHasKey('Courses', $suggested);
        self::assertArrayNotHasKey('Sorties', $suggested);
    }

    public function testSuggestWritesNothing(): void
    {
        $this->world();

        $this->call('suggest');

        self::assertSame([], $this->getMercureHub()->getUpdates());
        self::assertSame([], $this->getAsyncTransport()->getSent());
    }

    public function testApplySetsNextMonthForTheChosenCategoriesOnly(): void
    {
        $this->world();
        $loisirsOctober = $this->octoberEnvelope('leisure');
        self::assertNotNull($loisirsOctober);
        self::assertSame(20000, $loisirsOctober->getAmountCents());
        self::assertNull($this->octoberEnvelope('cinema'));

        $result = $this->call('apply', '2026-09', (string) $this->getFixture('leisure')->getId());

        self::assertTrue($result['success']);
        self::assertCount(1, $result['applied']);
        self::assertSame(25000, $this->octoberEnvelope('leisure')->getAmountCents());
        self::assertNull($this->octoberEnvelope('cinema'));
        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testApplyWithoutCategoriesAppliesEverySuggestion(): void
    {
        $this->world();

        $result = $this->call('apply');

        self::assertCount(3, $result['applied']);
        self::assertSame(25000, $this->octoberEnvelope('leisure')->getAmountCents());
        self::assertSame(2000, $this->octoberEnvelope('cinema')->getAmountCents());
        self::assertNull($this->octoberEnvelope('groceries'));
    }
}
