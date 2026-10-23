<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Mcp\Tool\ManageInvestmentsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** manage_investments (MAG-557): get, set and remove, and partial requests refused with `current`. */
class ManageInvestmentsToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/project_investment.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageInvestmentsTool::class);
        \assert($tool instanceof ManageInvestmentsTool);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    /** @return array<string, mixed> */
    private function box(): array
    {
        return [
            'projectId' => $this->id('box'),
            'downPaymentCents' => 400000,
            'monthlyRentCents' => 16000,
            'loan' => ['annualRateBasisPoints' => 390, 'durationMonths' => 120],
            'resale' => ['month' => '2028-06', 'netAmountCents' => 1000000, 'targetProjectId' => $this->id('car')],
        ];
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        $data = $this->call('set', ...$this->box());

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('project', $data);
    }

    public function testAnUnknownActionIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('Unknown action', $this->call('list')['error']);
    }

    public function testAnotherUsersProjectIsNotFound(): void
    {
        $this->loginFixtureUser();

        foreach (['get', 'set', 'remove'] as $action) {
            self::assertStringContainsString('Project not found', $this->call($action, projectId: $this->id('secret'))['error'], $action);
        }
    }

    public function testSetMakesTheProjectAnInvestmentPublishesAndIndexes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('set', ...$this->box());

        self::assertTrue($data['success']);
        $investment = $data['project']['investment'];
        self::assertSame(14108, $investment['loanPaymentCents']);
        self::assertSame(1892, $investment['monthlyBalanceCents']);
        self::assertSame('2028-06', $investment['resale']['month']);

        $car = $this->em()->find(Project::class, $this->id('car'));
        \assert($car instanceof Project);
        self::assertSame(200000, $car->getSalaryCents());

        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('box'));
        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('car'));
        $this->assertElasticsearchIndexDispatched(Project::class);

        self::assertSame($investment, $this->call('get', projectId: $this->id('box'))['investment']);
    }

    public function testSetWithMissingValuesIsRefusedWithTheCurrentState(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('set', projectId: $this->id('box'), monthlyRentCents: 16000);

        self::assertStringContainsString('downPaymentCents', $data['error']);
        self::assertSame($this->id('box'), $data['current']['id']);
        self::assertNull($data['current']['investment']);
        $this->assertMercureUpdateCount(0);
    }

    public function testRemoveTakesTheInvestmentAndItsResaleBack(): void
    {
        $this->loginFixtureUser();
        $this->call('set', ...$this->box());
        $this->resetMercure();

        $data = $this->call('remove', projectId: $this->id('box'));

        self::assertTrue($data['success']);
        self::assertNull($data['project']['investment']);
        $car = $this->em()->find(Project::class, $this->id('car'));
        \assert($car instanceof Project);
        self::assertSame(1200000, $car->getSalaryCents());
        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('car'));
    }
}
