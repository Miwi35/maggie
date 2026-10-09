<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Transaction;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * What a rule would catch, asked while it is still being written.
 */
class PreviewCategorizationRuleControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /**
     * @param array<string, mixed> $criteria
     *
     * @return array<string, mixed>
     */
    private function preview(array $criteria): array
    {
        $this->client->request(
            'POST',
            '/api/finance/categorization-rules/preview',
            [],
            [],
            $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'],
            json_encode($criteria, JSON_THROW_ON_ERROR),
        );

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function signIn(): void
    {
        $this->loadFixtures('rule_preview.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    /** @return list<string> */
    private function labels(array $preview): array
    {
        return array_column($preview['matches'], 'label');
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', '/api/finance/categorization-rules/preview', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode(['labelPattern' => 'netflix'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnEmptyPatternIsRefused(): void
    {
        $this->signIn();

        $this->preview(['labelPattern' => '']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testAMissingPatternIsRefused(): void
    {
        $this->signIn();

        $this->preview(['categoryId' => (string) $this->getFixture('subscriptions')->getId()]);

        self::assertResponseStatusCodeSame(400);
    }

    public function testInvertedAmountBoundsAreRefused(): void
    {
        $this->signIn();

        $data = $this->preview(['labelPattern' => 'netflix', 'minAmountCents' => 5000, 'maxAmountCents' => 1000]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('minimum amount', $data['error']);
    }

    public function testAnUnknownMatchTypeIsRefused(): void
    {
        $this->signIn();

        $this->preview(['labelPattern' => 'netflix', 'matchType' => 'regex']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testAFieldOfTheWrongTypeIsRefused(): void
    {
        $this->signIn();

        $this->preview(['labelPattern' => 'netflix', 'minAmountCents' => 'cheap']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testACategoryThatIsNotYoursIsRefused(): void
    {
        $this->signIn();

        $this->preview([
            'labelPattern' => 'netflix',
            'categoryId' => (string) $this->getFixture('other_subscriptions')->getId(),
        ]);

        self::assertResponseStatusCodeSame(400);
    }

    public function testACategoryThatIsNotAnIdIsRefused(): void
    {
        $this->signIn();

        $this->preview(['labelPattern' => 'netflix', 'categoryId' => 'not-an-id']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testItCountsWhatTheCriteriaRecogniseAndWhatWouldChange(): void
    {
        $this->signIn();

        $data = $this->preview([
            'labelPattern' => 'netflix',
            'categoryId' => (string) $this->getFixture('subscriptions')->getId(),
        ]);

        self::assertResponseIsSuccessful();
        // The refund is a credit: an expense heading cannot hold it, so the rule claims nothing there.
        self::assertSame(6, $data['total']);
        self::assertSame(3, $data['changeCount']);
        self::assertEqualsCanonicalizing([
            'NETFLIX.COM 4412', 'NETFLIX.COM 4413', 'NETFLIX.COM 4414',
            'NETFLIX.COM BY HAND', 'NETFLIX.COM ALREADY FILED', 'NETFLIX PREMIUM 4K',
        ], $this->labels($data));
    }

    public function testLinesComeNewestFirstWithWhatTheyAreFiledUnderNow(): void
    {
        $this->signIn();
        $leisure = (string) $this->getFixture('leisure')->getId();

        $data = $this->preview([
            'labelPattern' => 'netflix',
            'categoryId' => (string) $this->getFixture('subscriptions')->getId(),
        ]);

        $dates = array_column($data['matches'], 'bookedAt');
        $sorted = $dates;
        rsort($sorted);
        self::assertSame($sorted, $dates);

        $lines = array_column($data['matches'], null, 'label');
        self::assertSame((string) $this->getFixture('netflix_september')->getId(), $lines['NETFLIX.COM 4414']['transactionId']);
        self::assertSame(-1349, $lines['NETFLIX.COM 4414']['amountCents']);
        self::assertNull($lines['NETFLIX.COM 4414']['currentCategoryId']);
        self::assertTrue($lines['NETFLIX.COM 4414']['wouldChange']);
        self::assertSame($leisure, $lines['NETFLIX.COM BY HAND']['currentCategoryId']);
    }

    public function testALineFiledByHandOrAlreadyFiledWouldNotChange(): void
    {
        $this->signIn();

        $data = $this->preview([
            'labelPattern' => 'netflix',
            'categoryId' => (string) $this->getFixture('subscriptions')->getId(),
        ]);

        $lines = array_column($data['matches'], null, 'label');
        self::assertFalse($lines['NETFLIX.COM BY HAND']['wouldChange']);
        // Already in the category the rule would give it.
        self::assertFalse($lines['NETFLIX.COM ALREADY FILED']['wouldChange']);
    }

    public function testALineAHigherPriorityRuleClaimsWouldNotChange(): void
    {
        $this->signIn();
        $criteria = [
            'labelPattern' => 'netflix',
            'categoryId' => (string) $this->getFixture('subscriptions')->getId(),
        ];

        $lines = array_column($this->preview($criteria)['matches'], null, 'label');
        self::assertFalse($lines['NETFLIX PREMIUM 4K']['wouldChange']);

        // Raised above the "PREMIUM" rule, the same criteria take the line.
        $outranking = $this->preview($criteria + ['priority' => 100]);
        self::assertSame(4, $outranking['changeCount']);
    }

    public function testEditingARuleReplacesItInThePreview(): void
    {
        $this->signIn();

        $data = $this->preview([
            'labelPattern' => 'netflix',
            'categoryId' => (string) $this->getFixture('subscriptions')->getId(),
            'ruleId' => (string) $this->getFixture('rule_premium')->getId(),
        ]);

        // The "PREMIUM" rule is the one being edited: it no longer stands in the way.
        self::assertSame(4, $data['changeCount']);
    }

    public function testAnEditedRuleKeepsItsPlaceAmongRulesOfEqualPriority(): void
    {
        $this->signIn();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $older = $this->getFixture('rule_premium');
        // Created after it, so it comes after it on a tie.
        $newer = (new CategorizationRule())
            ->setUser($older->getUser())
            ->setLabelPattern('premium')
            ->setCategory($this->getFixture('subscriptions'))
            ->setPriority(0);
        $em->persist($newer);
        $em->flush();

        $criteria = [
            'labelPattern' => 'premium',
            'categoryId' => (string) $this->getFixture('leisure')->getId(),
            'priority' => 0,
        ];

        // Edited, the older rule is still read first: it takes the line, as the real application would.
        $edited = $this->preview($criteria + ['ruleId' => (string) $older->getId()]);
        self::assertSame(1, $edited['changeCount']);

        // Written fresh, the same criteria come last and the older rule keeps the line.
        $fresh = $this->preview($criteria);
        self::assertSame(0, $fresh['changeCount']);
    }

    public function testADisabledRuleChangesNothing(): void
    {
        $this->signIn();

        $data = $this->preview([
            'labelPattern' => 'netflix',
            'categoryId' => (string) $this->getFixture('subscriptions')->getId(),
            'isActive' => false,
        ]);

        self::assertSame(6, $data['total']);
        self::assertSame(0, $data['changeCount']);
    }

    public function testWithoutACategoryLinesAreListedButNoneWouldChange(): void
    {
        $this->signIn();

        $data = $this->preview(['labelPattern' => 'netflix']);

        self::assertResponseIsSuccessful();
        self::assertSame(7, $data['total']);
        self::assertSame(0, $data['changeCount']);
        self::assertNotContains(true, array_column($data['matches'], 'wouldChange'));
    }

    public function testTheCriteriaNarrowTheMatch(): void
    {
        $this->signIn();
        $subscriptions = (string) $this->getFixture('subscriptions')->getId();

        $starts = $this->preview(['labelPattern' => 'netflix.com', 'matchType' => 'starts_with', 'categoryId' => $subscriptions]);
        self::assertSame(5, $starts['total']);

        $equals = $this->preview(['labelPattern' => 'netflix premium 4k', 'matchType' => 'equals', 'categoryId' => $subscriptions]);
        self::assertSame(['NETFLIX PREMIUM 4K'], $this->labels($equals));

        $pricey = $this->preview(['labelPattern' => 'netflix', 'minAmountCents' => 1500, 'categoryId' => $subscriptions]);
        self::assertSame(['NETFLIX PREMIUM 4K'], $this->labels($pricey));

        $credits = $this->preview(['labelPattern' => 'netflix', 'direction' => 'credit']);
        self::assertSame(['NETFLIX REMBOURSEMENT'], $this->labels($credits));
    }

    public function testOnlyYourOwnTransactionsAreListed(): void
    {
        $this->signIn();

        $data = $this->preview(['labelPattern' => 'netflix']);

        self::assertNotContains('NETFLIX.COM OTHER', $this->labels($data));
    }

    public function testNoMoreThanFiftyLinesAreReturnedButTheTotalIsComplete(): void
    {
        $this->signIn();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $template = $this->getFixture('netflix_july');

        for ($i = 0; $i < 55; ++$i) {
            $em->persist((new Transaction())
                ->setUser($template->getUser())
                ->setAccount($template->getAccount())
                ->setAmountCents(-900)
                ->setBookedAt(new \DateTimeImmutable('2026-01-01'))
                ->setLabel('NETFLIX.COM BULK '.$i)
                ->setStatus($template->getStatus()));
        }
        $em->flush();

        $data = $this->preview([
            'labelPattern' => 'netflix',
            'categoryId' => (string) $this->getFixture('subscriptions')->getId(),
        ]);

        self::assertSame(61, $data['total']);
        self::assertSame(58, $data['changeCount']);
        self::assertCount(50, $data['matches']);
        // The newest 50: the older bulk lines are the ones cut.
        self::assertContains('NETFLIX.COM 4414', $this->labels($data));
    }

    public function testPreviewingWritesAndPublishesNothing(): void
    {
        $this->signIn();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $rulesBefore = $em->getRepository(CategorizationRule::class)->count([]);

        $this->preview([
            'labelPattern' => 'netflix',
            'categoryId' => (string) $this->getFixture('subscriptions')->getId(),
        ]);

        $em->clear();
        self::assertSame($rulesBefore, $em->getRepository(CategorizationRule::class)->count([]));
        // Only the line that was already filed there: the preview moved nothing.
        self::assertCount(1, $em->getRepository(Transaction::class)->findBy(['category' => $this->getFixture('subscriptions')->getId()]));
        $this->assertNothingPublishedOn('/transactions/');
        $this->assertNoElasticsearchIndexDispatched(Transaction::class);
    }
}
