<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\UseCase\ApplyCategorizationRule;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * One rule applied to the history: what it would have filed itself, nothing else.
 */
class ApplyCategorizationRuleTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private ApplyCategorizationRule $apply;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->apply = self::getContainer()->get(ApplyCategorizationRule::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->loadFixtures(dirname(__DIR__).'/Controller/fixtures/rule_preview.yaml');
    }

    private function categoryOf(string $fixture): ?string
    {
        $this->em->clear();
        $category = $this->em->find(Transaction::class, $this->getFixture($fixture)->getId())->getCategory();

        return $category ? (string) $category->getId() : null;
    }

    private function netflixRule(int $priority = 0): CategorizationRule
    {
        $rule = (new CategorizationRule())
            ->setUser($this->getFixture('test_user'))
            ->setLabelPattern('netflix')
            ->setCategory($this->getFixture('subscriptions'))
            ->setPriority($priority);
        $this->em->persist($rule);
        $this->em->flush();
        $this->resetMercure();
        $this->resetAsyncTransport();

        return $rule;
    }

    public function testItFilesTheUncategorizedLinesTheRuleWins(): void
    {
        $rule = $this->netflixRule();
        $subscriptions = (string) $this->getFixture('subscriptions')->getId();
        $leisure = (string) $this->getFixture('leisure')->getId();

        $result = $this->apply->execute($rule);

        foreach (['netflix_july', 'netflix_august', 'netflix_september'] as $line) {
            self::assertSame($subscriptions, $this->categoryOf($line), $line);
        }
        // The "PREMIUM" rule outranks it, and a manual choice is never overwritten.
        self::assertNull($this->categoryOf('netflix_premium'));
        self::assertSame($leisure, $this->categoryOf('netflix_by_hand'));
        self::assertSame(3, $result['categorized']);

        $em = $this->em;
        $em->clear();
        $line = $em->find(Transaction::class, $this->getFixture('netflix_july')->getId());
        self::assertSame(CategorySource::Rule, $line->getCategorySource());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testARuleThatOutranksTheOthersTakesTheContestedLine(): void
    {
        $rule = $this->netflixRule(priority: 100);

        $result = $this->apply->execute($rule);

        self::assertSame(4, $result['categorized']);
        self::assertSame((string) $this->getFixture('subscriptions')->getId(), $this->categoryOf('netflix_premium'));
    }

    public function testItLeavesOtherPeoplesLinesAlone(): void
    {
        $this->apply->execute($this->netflixRule());

        self::assertNull($this->categoryOf('other_netflix'));
    }

    public function testADisabledRuleFilesNothing(): void
    {
        $rule = $this->netflixRule();
        $rule->setIsActive(false);
        $this->em->flush();

        $result = $this->apply->execute($rule);

        self::assertSame(0, $result['categorized']);
        self::assertNull($this->categoryOf('netflix_july'));
    }
}
