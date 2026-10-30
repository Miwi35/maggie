<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\HealthProfile;
use Maggie\Health\Mcp\Tool\GetHealthProfileTool;
use Maggie\Health\Mcp\Tool\UpdateHealthProfileTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** get_health_profile and update_health_profile (MAG-707): « Mon objectif de pas, c'est 10 000 » first. */
class HealthProfileToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ElasticsearchAssertionTrait;
    use MercureAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-30T09:00:00+00:00')));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/health_profile.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function get(): array
    {
        return json_decode(self::getContainer()->get(GetHealthProfileTool::class)(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function update(mixed ...$arguments): array
    {
        return json_decode(self::getContainer()->get(UpdateHealthProfileTool::class)(...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function profileOf(string $userRef): ?HealthProfile
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $em->clear();
        $user = $this->getFixture($userRef);
        \assert($user instanceof User);

        return $em->getRepository(HealthProfile::class)->findOneBy(['user' => $user->getId()]);
    }

    public function testWithoutAUserBothAnswerAnError(): void
    {
        self::assertArrayHasKey('error', $this->get());
        self::assertArrayHasKey('error', $this->update(stepGoal: 10000));
        self::assertNull($this->profileOf('test_user'));
    }

    public function testMyStepGoalIs10000UpdatesTheProfileAndPublishesIt(): void
    {
        $this->loginFixtureUser();

        $data = $this->update(stepGoal: 10000);

        self::assertTrue($data['success']);
        self::assertSame(10000, $data['profile']['stepGoal']);
        $stored = $this->profileOf('test_user');
        self::assertNotNull($stored);
        self::assertSame(10000, $stored->getStepGoal());
        $this->assertMercureUpdatePublished('/api/health-profile/'.$stored->getId());
        // The other fields keep their defaults.
        self::assertSame(20, $stored->getSessionMinutes());
    }

    public function testTheReadGivesTheFiguresOfTheUsersOwnProfile(): void
    {
        $this->loginFixtureUser();
        $this->update(sex: 'male', birthYear: 1988, heightCm: 178);

        $data = $this->get();

        self::assertSame(1648, $data['bmrKcal']);
        self::assertSame(1980, $data['needKcal']);
        self::assertEquals(72.0, $data['weightKg']);
        self::assertSame((string) $this->profileOf('test_user')?->getId(), $data['id']);
    }

    public function testTheNeighboursProfileIsNeverRead(): void
    {
        $this->loginFixtureUser();

        $data = $this->get();

        self::assertNull($data['id']);
        self::assertNull($data['heightCm']);
        self::assertSame(8000, $data['stepGoal']);
    }

    public function testNothingToChangeIsRefusedWithTheCurrentProfile(): void
    {
        $this->loginFixtureUser();

        $data = $this->update();

        self::assertArrayHasKey('error', $data);
        self::assertSame(8000, $data['current']['stepGoal']);
        $this->assertMercureUpdateCount(0);
    }

    public function testARefusedValueNamesItsFieldAndChangesNothing(): void
    {
        $this->loginFixtureUser();

        $data = $this->update(stepGoal: 10000, sessionMinutes: 25);

        self::assertSame('sessionMinutes', $data['field']);
        self::assertNull($this->profileOf('test_user'));
        $this->assertMercureUpdateCount(0);
    }
}
