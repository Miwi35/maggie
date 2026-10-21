<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Mcp\Tool\ManageProjectFundingTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** manage_project_funding (MAG-524): set and options, and partial requests refused with `current`. */
class ProjectFundingToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/project_funding.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageProjectFundingTool::class);
        \assert($tool instanceof ManageProjectFundingTool);

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

    /** @return list<array<string, mixed>> */
    private function carSources(): array
    {
        return [
            ['kind' => 'savings', 'amountCents' => 100000, 'account' => $this->id('livret_jeune')],
            ['kind' => 'loan', 'amountCents' => 600000, 'loan' => ['annualRateBasisPoints' => 400, 'durationMonths' => 24]],
        ];
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        $data = $this->call('set', projectId: $this->id('car'), sources: []);

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

        $data = $this->call('set', projectId: $this->id('secret'), sources: []);

        self::assertStringContainsString('Project not found', $data['error']);
    }

    public function testSetFundsTheProjectPublishesAndIndexes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('set', projectId: $this->id('car'), sources: $this->carSources());

        self::assertTrue($data['success']);
        self::assertSame(300000, $data['project']['salaryCents']);
        self::assertSame(['savings', 'loan'], array_column($data['project']['fundingSources'], 'kind'));

        $project = $this->em()->find(Project::class, $this->id('car'));
        \assert($project instanceof Project);
        self::assertSame(300000, $project->getSalaryCents());
        $loan = $project->loanSource()?->getLoan();
        self::assertInstanceOf(Loan::class, $loan);
        self::assertTrue($loan->isProjected());
        self::assertSame(600000, $loan->getPrincipalRemainingCents());

        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('car'));
        $this->assertElasticsearchIndexDispatched(Project::class);
    }

    public function testSetWithoutSourcesIsRefusedWithTheCurrentState(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('set', projectId: $this->id('car'));

        self::assertStringContainsString('sources is required', $data['error']);
        self::assertSame($this->id('car'), $data['current']['id']);
        self::assertSame([], $data['current']['fundingSources']);
        $this->assertMercureUpdateCount(0);
    }

    public function testAPartialLoanIsRefusedWithTheCurrentState(): void
    {
        $this->loginFixtureUser();
        $this->call('set', projectId: $this->id('car'), sources: $this->carSources());
        $this->resetMercure();

        $data = $this->call('set', projectId: $this->id('car'), sources: [
            ['kind' => 'loan', 'amountCents' => 800000, 'loan' => ['annualRateBasisPoints' => 350]],
        ]);

        self::assertStringContainsString('durationMonths', $data['error']);
        self::assertSame(300000, $data['current']['salaryCents']);
        self::assertCount(2, $data['current']['fundingSources']);
        self::assertSame(600000, $this->em()->getRepository(Loan::class)->findOneBy(['projected' => true])?->getPrincipalRemainingCents());
        $this->assertMercureUpdateCount(0);
    }

    public function testOptionsArePricedAsFacts(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('options', projectId: $this->id('car'), loanRateBasisPoints: 400, loanMonths: 24, downPaymentCents: 400000, yieldBasisPoints: 300);

        self::assertSame(20, $data['cash']['availableInMonths']);
        self::assertSame(43425, $data['loan']['monthlyPaymentCents']);
        self::assertSame(8, $data['partial']['availableInMonths']);
        self::assertNull($data['loan']['nImpactMonths']);
    }

    public function testOptionsWithoutTheLoanConditionsListThemWithTheCurrentState(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('options', projectId: $this->id('car'), downPaymentCents: 400000);

        self::assertStringContainsString('loanRateBasisPoints, loanMonths', $data['error']);
        self::assertStringContainsString('yieldBasisPoints', $data['error']);
        self::assertSame($this->id('car'), $data['current']['id']);
    }
}
