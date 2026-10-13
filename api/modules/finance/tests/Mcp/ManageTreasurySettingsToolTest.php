<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\TreasurySettings;
use Maggie\Finance\Mcp\Tool\ManageTreasurySettingsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** The expected salary day over MCP (MAG-276): read, created on first read, written through the bus. */
class ManageTreasurySettingsToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    private const string FIXTURES = __DIR__.'/../UseCase/fixtures/savings_advances.yaml';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(self::FIXTURES);
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $action, ?int $salaryExpectedDay = null, ?bool $clearSalaryExpectedDay = null): array
    {
        $tool = self::getContainer()->get(ManageTreasurySettingsTool::class);

        return json_decode($tool($action, $salaryExpectedDay, $clearSalaryExpectedDay), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function settingsOf(string $userRef): ?TreasurySettings
    {
        $this->em()->clear();
        $user = $this->getFixture($userRef);
        \assert($user instanceof User);

        return $this->em()->getRepository(TreasurySettings::class)->findOneBy(['user' => $user->getId()]);
    }

    public function testWithoutAUserBoundItReportsTheError(): void
    {
        self::assertArrayHasKey('error', $this->call('get'));
        self::assertArrayHasKey('error', $this->call('update', 25));
    }

    public function testAnUnknownActionIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('Unknown action', $this->call('delete')['error']);
    }

    public function testUpdateWithoutTheDayIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('salaryExpectedDay', $this->call('update')['error']);
        self::assertSame(25, $this->settingsOf('test_user')?->getSalaryExpectedDay());
    }

    public function testADayOutsideTheMonthIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->call('update', 32));
        self::assertArrayHasKey('error', $this->call('update', 0));
        self::assertSame(25, $this->settingsOf('test_user')?->getSalaryExpectedDay());
    }

    public function testGetReadsTheDayAndTheDelay(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('get');

        self::assertSame(25, $data['salaryExpectedDay']);
        self::assertTrue($data['salaryDelay']['isConfigured']);
        self::assertSame(495000, $data['salaryDelay']['referenceCents']);
    }

    public function testTheSettingsAreCreatedOnFirstRead(): void
    {
        $this->loginFixtureUser('bare_user');
        self::assertNull($this->settingsOf('bare_user'));

        $data = $this->call('get');

        $created = $this->settingsOf('bare_user');
        self::assertNotNull($created);
        self::assertNull($created->getSalaryExpectedDay());
        self::assertSame((string) $created->getId(), $data['id']);
        self::assertFalse($data['salaryDelay']['isConfigured']);
        $this->assertMercurePublishedOnce('/api/treasury_settings/'.$created->getId());
        // Not indexed: nothing searches the settings.
        $this->assertNoElasticsearchIndexDispatched(TreasurySettings::class);
    }

    public function testUpdateWritesTheDayAndPublishesIt(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', 28);

        self::assertTrue($data['success']);
        self::assertSame(28, $data['salaryExpectedDay']);
        $stored = $this->settingsOf('test_user');
        self::assertSame(28, $stored?->getSalaryExpectedDay());
        $topic = '/api/treasury_settings/'.$stored->getId();
        $this->assertMercureUpdatePublished('/users/'.$this->getFixture('test_user')->getId().$topic);
        self::assertSame(28, $this->mercurePayloadsOn($topic)[0]['salaryExpectedDay']);
        $this->assertNoElasticsearchIndexDispatched(TreasurySettings::class);
    }

    public function testClearForgetsTheDay(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', clearSalaryExpectedDay: true);

        self::assertNull($data['salaryExpectedDay']);
        self::assertFalse($data['salaryDelay']['isConfigured']);
        self::assertNull($this->settingsOf('test_user')?->getSalaryExpectedDay());
    }

    public function testAUserOnlyChangesHisOwnSettings(): void
    {
        $this->loginFixtureUser();

        $this->call('update', 5);

        self::assertSame(25, $this->settingsOf('other_user')?->getSalaryExpectedDay());
        $this->assertNothingPublishedOn('/api/treasury_settings/'.$this->settingsOf('other_user')?->getId());
    }
}
