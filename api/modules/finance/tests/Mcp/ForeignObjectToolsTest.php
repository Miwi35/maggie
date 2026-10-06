<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\ManageAccountsTool;
use Maggie\Finance\Mcp\Tool\ManageCategoriesTool;
use Maggie\Finance\Mcp\Tool\ManageCategorizationRulesTool;
use Maggie\Finance\Mcp\Tool\ManageEnvelopesTool;
use Maggie\Finance\Mcp\Tool\ManageLoansTool;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** An id from another user must read as "not found": nothing changes, nothing is published. */
class ForeignObjectToolsTest extends KernelTestCase
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
        $this->loadFixtures('foreign_objects.yaml');
        $this->loginFixtureUser();
    }

    /** @return iterable<string, array{class-string, string, string, class-string, string, mixed}> */
    public static function foreignTargets(): iterable
    {
        yield 'transactions' => [ManageTransactionsTool::class, 'transactionId', 'foreign_transaction', Transaction::class, 'getLabel', 'Supermarché étranger'];
        yield 'envelopes' => [ManageEnvelopesTool::class, 'envelopeId', 'foreign_envelope', Envelope::class, 'getAmountCents', 40000];
        yield 'categories' => [ManageCategoriesTool::class, 'categoryId', 'foreign_category', Category::class, 'getName', 'Catégorie étrangère'];
        yield 'categorization rules' => [ManageCategorizationRulesTool::class, 'categorizationRuleId', 'foreign_rule', CategorizationRule::class, 'getLabelPattern', 'CARREFOUR'];
        yield 'accounts' => [ManageAccountsTool::class, 'accountId', 'foreign_account', Account::class, 'getName', 'Compte étranger'];
        yield 'loans' => [ManageLoansTool::class, 'loanId', 'foreign_loan', Loan::class, 'getName', 'Crédit étranger'];
    }

    /** @return array<string, mixed> the changed field the update of each tool takes */
    private static function changeFor(string $getter): array
    {
        return match ($getter) {
            'getLabel' => ['label' => 'Hacked'],
            'getAmountCents' => ['amountCents' => 1],
            'getLabelPattern' => ['labelPattern' => 'HACKED'],
            default => ['name' => 'Hacked'],
        };
    }

    private function reload(string $class, string $ref): ?object
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository($class)->find($this->getFixture($ref)->getId());
    }

    /** @return array<string, mixed> */
    private function decode(string $result): array
    {
        return json_decode($result, true, 512, JSON_THROW_ON_ERROR);
    }

    private function assertRefused(array $result, string $expected = 'not found'): void
    {
        self::assertArrayHasKey('error', $result);
        self::assertStringContainsString($expected, $result['error']);
        $this->assertMercureUpdateCount(0);
        self::assertSame([], $this->getAsyncTransport()->getSent());
    }

    #[\PHPUnit\Framework\Attributes\DataProvider('foreignTargets')]
    public function testUpdatingAnotherUsersObjectIsRefused(string $toolClass, string $idArgument, string $ref, string $entityClass, string $getter, mixed $original): void
    {
        $id = (string) $this->getFixture($ref)->getId();

        $result = $this->decode(self::getContainer()->get($toolClass)(
            'update',
            ...[$idArgument => $id],
            ...self::changeFor($getter),
        ));

        $this->assertRefused($result);
        self::assertSame($original, $this->reload($entityClass, $ref)->$getter());
    }

    #[\PHPUnit\Framework\Attributes\DataProvider('foreignTargets')]
    public function testDeletingAnotherUsersObjectIsRefused(string $toolClass, string $idArgument, string $ref, string $entityClass, string $getter, mixed $original): void
    {
        $id = (string) $this->getFixture($ref)->getId();

        $result = $this->decode(self::getContainer()->get($toolClass)('delete', ...[$idArgument => $id]));

        $this->assertRefused($result);
        self::assertNotNull($this->reload($entityClass, $ref));
    }

    public function testCategorizingAnotherUsersTransactionIsRefused(): void
    {
        $result = $this->decode(self::getContainer()->get(ManageTransactionsTool::class)(
            'categorize',
            transactionId: (string) $this->getFixture('foreign_transaction')->getId(),
            categoryId: (string) $this->getFixture('own_category')->getId(),
        ));

        $this->assertRefused($result);
        self::assertSame(
            'Catégorie étrangère',
            $this->reload(Transaction::class, 'foreign_transaction')->getCategory()->getName(),
        );
    }

    public function testFilingATransactionUnderAnotherUsersCategoryIsRefused(): void
    {
        $result = $this->decode(self::getContainer()->get(ManageTransactionsTool::class)(
            'update',
            transactionId: (string) $this->getFixture('own_transaction')->getId(),
            categoryId: (string) $this->getFixture('foreign_category')->getId(),
        ));

        $this->assertRefused($result, 'Category not found');
        self::assertNull($this->reload(Transaction::class, 'own_transaction')->getCategory());
    }

    public function testMovingATransactionToAnotherUsersAccountIsRefused(): void
    {
        $result = $this->decode(self::getContainer()->get(ManageTransactionsTool::class)(
            'update',
            transactionId: (string) $this->getFixture('own_transaction')->getId(),
            accountId: (string) $this->getFixture('foreign_account')->getId(),
        ));

        $this->assertRefused($result, 'Account not found');
        self::assertSame('Mon compte', $this->reload(Transaction::class, 'own_transaction')->getAccount()->getName());
    }

    public function testLearningFromAnotherUsersTransactionIsRefusedAndCreatesNoRule(): void
    {
        $result = $this->decode(self::getContainer()->get(ManageCategorizationRulesTool::class)(
            'learn',
            transactionId: (string) $this->getFixture('foreign_transaction')->getId(),
            categoryId: (string) $this->getFixture('own_category')->getId(),
        ));

        $this->assertRefused($result, 'Transaction not found');
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(0, $em->getRepository(CategorizationRule::class)->findBy(['user' => $this->getFixture('test_user')]));
    }

    public function testSettingAnEnvelopeOnAnotherUsersCategoryIsRefused(): void
    {
        $result = $this->decode(self::getContainer()->get(ManageEnvelopesTool::class)(
            'set',
            categoryId: (string) $this->getFixture('foreign_category')->getId(),
            amountCents: 5000,
            year: 2026,
            month: 7,
        ));

        $this->assertRefused($result, 'Category not found');
        self::assertSame(40000, $this->reload(Envelope::class, 'foreign_envelope')->getAmountCents());
    }
}
