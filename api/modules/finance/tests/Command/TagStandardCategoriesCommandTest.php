<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Category\StandardCategories;
use Maggie\Finance\Command\TagStandardCategoriesCommand;
use Maggie\Finance\Entity\Category;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * `app:finance:categories:tag-standard` — gives their standard key to the
 * « Salaire » and « Primes » made by hand before it existed (MAG-273).
 */
final class TagStandardCategoriesCommandTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('TagStandardCategoriesCommandTest.yaml');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function tester(): CommandTester
    {
        $application = new Application();
        $application->add(self::getContainer()->get(TagStandardCategoriesCommand::class));

        return new CommandTester($application->find('app:finance:categories:tag-standard'));
    }

    private function keyOf(string $fixture): ?string
    {
        return $this->em()->find(Category::class, $this->getFixture($fixture)->getId())?->getStandardKey();
    }

    public function testItTagsTheHandMadeCategoriesAndTouchesNothingElse(): void
    {
        $tester = $this->tester();

        self::assertSame(Command::SUCCESS, $tester->execute([]));

        $this->em()->clear();
        self::assertSame(StandardCategories::SALARY, $this->keyOf('hand_salary'));
        self::assertSame(StandardCategories::BONUS, $this->keyOf('hand_bonus'));
        self::assertNull($this->keyOf('housing'));
        $salary = $this->em()->find(Category::class, $this->getFixture('hand_salary')->getId());
        self::assertSame(' salaire ', $salary->getName());
        self::assertSame('#123456', $salary->getColor());
        self::assertStringContainsString('salary', $tester->getDisplay());

        $this->assertMercureUpdatePublished('/categories/');
        $this->assertElasticsearchIndexDispatchedFor(Category::class, (string) $this->getFixture('hand_salary')->getId());
    }

    /** A key already held stays where it is: the renamed salary keeps it, the second « Salaire » gets nothing. */
    public function testAKeyAlreadyHeldIsNotMoved(): void
    {
        self::assertSame(Command::SUCCESS, $this->tester()->execute(['email' => 'tag-standard-tagged@example.com']));

        $this->em()->clear();
        self::assertSame(StandardCategories::SALARY, $this->keyOf('renamed_salary'));
        self::assertNull($this->keyOf('second_salary'));
        self::assertNull($this->keyOf('hand_salary'), 'only the account named is touched');
    }

    public function testRunningItTwiceChangesNothing(): void
    {
        $this->tester()->execute([]);
        $this->resetAsyncTransport();

        $tester = $this->tester();
        self::assertSame(Command::SUCCESS, $tester->execute([]));

        self::assertStringContainsString('Aucune catégorie à marquer', $tester->getDisplay());
        $this->assertNoElasticsearchIndexDispatched(Category::class);
    }

    public function testAnUnknownEmailFails(): void
    {
        self::assertSame(Command::FAILURE, $this->tester()->execute(['email' => 'nobody@example.com']));
    }
}
