<?php

declare(strict_types=1);

namespace Maggie\Core\Command;

use Maggie\Core\Entity\User;
use Symfony\Component\Console\Attribute\AsCommand;

#[AsCommand(name: 'app:user:grant', description: 'Grant a permission (role) to a user')]
final class GrantRoleCommand extends AbstractUserRoleCommand
{
    protected function apply(User $user, string $role): void
    {
        $user->addRole($role);
    }

    protected function successMessage(string $email, string $role): string
    {
        return sprintf('%s has %s.', $email, $role);
    }
}
