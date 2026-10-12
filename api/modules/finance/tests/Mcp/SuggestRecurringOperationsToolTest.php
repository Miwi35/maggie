<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\SuggestRecurringOperationsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * `suggest_recurring_operations` (MAG-47): the series the history implies,
 * proposed by Maggie and created only on the user's yes.
 */
class SuggestRecurringOperationsToolTest extends KernelTestCase
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
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/recurring_suggestions.yaml');
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(SuggestRecurringOperationsTool::class);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, array<string, mixed>> */
    private function listed(): array
    {
        return array_column($this->call('list')['suggestions'], null, 'label');
    }

    private function seriesCount(): int
    {
        return self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(RecurringOperation::class)->count([]);
    }

    public function testAListNobodyIsBoundToIsRefused(): void
    {
        self::assertSame(MissingMcpUserException::MESSAGE, $this->call('list')['error']);
    }

    public function testAnAcceptNobodyIsBoundToIsRefused(): void
    {
        $data = $this->call('accept', suggestions: [['label' => 'Flixo']]);

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertSame(1, $this->seriesCount());
    }

    public function testAnUnknownActionIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('Unknown action', $this->call('cancel')['error']);
    }

    public function testAcceptRequiresTheSuggestions(): void
    {
        $this->loginFixtureUser();

        self::assertSame('suggestions is required for accept.', $this->call('accept')['error']);
        self::assertSame('suggestions is required for accept.', $this->call('accept', suggestions: [])['error']);
    }

    public function testAMinOccurrencesBelowTwoIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertSame('minOccurrences must be at least 2.', $this->call('list', minOccurrences: 1)['error']);
    }

    public function testListProposesTheSeriesAndWritesNothing(): void
    {
        $this->loginFixtureUser();

        $suggestions = $this->listed();

        self::assertSame('monthly', $suggestions['Flixo']['period']);
        self::assertSame(-1349, $suggestions['Flixo']['referenceAmountCents']);
        self::assertSame('quarterly', $suggestions['ASSURANCE HABITATION']['period']);
        self::assertSame(1, $this->seriesCount());
    }

    public function testListOnlyShowsTheBoundUsersHistory(): void
    {
        $this->loginFixtureUser();

        $flixo = $this->listed()['Flixo'];

        // The other user has three more Flixo debits: they must not be counted.
        self::assertSame(3, $flixo['occurrences']);
    }

    public function testAcceptCreatesTheSeriesAndAttachesItsHistory(): void
    {
        $this->loginFixtureUser();
        $flixo = $this->listed()['Flixo'];
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->call('accept', suggestions: [$flixo]);

        self::assertTrue($data['success']);
        self::assertSame(1, $data['created']);
        self::assertSame(3, $data['attached']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $series = $em->getRepository(RecurringOperation::class)->findOneBy(['label' => 'Flixo']);
        self::assertNotNull($series);
        self::assertCount(3, $em->getRepository(Transaction::class)->findBy(['recurringOperation' => $series]));

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    public function testAcceptWithNothingUsableIsRefused(): void
    {
        $this->loginFixtureUser();
        $rent = $this->listed()['LOYER SCI VERDA'];

        $data = $this->call('accept', suggestions: [$rent]);

        self::assertSame('No usable suggestion in the request.', $data['error']);
        self::assertSame(1, $this->seriesCount());
    }

    public function testAcceptIgnoresAnotherUsersCategory(): void
    {
        $this->loginFixtureUser();
        $flixo = $this->listed()['Flixo'];

        $data = $this->call('accept', suggestions: [['categoryId' => (string) $this->getFixture('other_secret')->getId()] + $flixo]);

        self::assertArrayHasKey('error', $data);
        self::assertSame(1, $this->seriesCount());
    }
}
