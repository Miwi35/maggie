<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Mcp\Tool\SetSickTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * set_sick (MAG-810): sick from today in the user's timezone, until they say it
 * is over. 23:30 UTC on the 12th is already the 13th in Paris.
 */
final class SetSickToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-11-12 23:30:00 UTC'));
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/user_preference.yaml');
        $this->resetMercure();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @return array<string, mixed> */
    private function call(bool $sick): array
    {
        $tool = self::getContainer()->get(SetSickTool::class);
        \assert($tool instanceof SetSickTool);

        return json_decode($tool($sick), true, 512, JSON_THROW_ON_ERROR);
    }

    private function sickSinceOf(User $user): ?string
    {
        $this->em()->clear();

        return $this->em()->getRepository(UserPreference::class)->findOneBy(['user' => $user])?->getSickSince()?->format('Y-m-d');
    }

    public function testSickTrueSetsToday(): void
    {
        $user = $this->loginFixtureUser();

        self::assertSame(['sick' => true, 'sickSince' => '2026-11-13'], $this->call(true));
        self::assertSame('2026-11-13', $this->sickSinceOf($user));
    }

    public function testSickTrueTwiceKeepsTheFirstDay(): void
    {
        $user = $this->loginFixtureUser();
        $this->call(true);
        Clock::set(new MockClock('2026-11-15 10:00:00 UTC'));

        self::assertSame(['sick' => true, 'sickSince' => '2026-11-13'], $this->call(true));
        self::assertSame('2026-11-13', $this->sickSinceOf($user));
    }

    public function testSickFalseClears(): void
    {
        $user = $this->loginFixtureUser();
        $this->call(true);

        self::assertSame(['sick' => false, 'sickSince' => null], $this->call(false));
        self::assertNull($this->sickSinceOf($user));
    }

    public function testMissingUserAnswersAnError(): void
    {
        $result = $this->call(true);

        self::assertArrayHasKey('error', $result);
        self::assertArrayNotHasKey('sickSince', $result);
        self::assertNull($this->em()->getRepository(UserPreference::class)->findOneBy([])?->getSickSince());
        $this->assertMercureUpdateCount(0);
    }

    public function testNeverTouchesAnotherUsersPreference(): void
    {
        $other = (new User())->setEmail('other@example.com')->setGoogleId('google-other')->setName('Other');
        $this->em()->persist($other);
        $this->em()->persist((new UserPreference())->setUser($other));
        $this->em()->flush();
        $this->loginFixtureUser();

        $this->call(true);

        self::assertNull($this->sickSinceOf($other));
    }

    public function testAUserWithoutPreferencesGetsOne(): void
    {
        $user = $this->loginFixtureUser();
        $this->em()->remove($this->em()->getRepository(UserPreference::class)->findOneBy(['user' => $user]));
        $this->em()->flush();

        // Paris, the default timezone of a new preference.
        self::assertSame(['sick' => true, 'sickSince' => '2026-11-13'], $this->call(true));
        self::assertSame('2026-11-13', $this->sickSinceOf($user));
    }

    public function testPublishesThePreference(): void
    {
        $this->loginFixtureUser();

        $this->call(true);

        $this->assertMercureUpdatePublished('/api/user_preferences/');
    }
}
