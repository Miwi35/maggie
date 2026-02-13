<?php

namespace Maggie\Core\Contract;

use Maggie\Core\Entity\User;

interface OwnedByUserInterface
{
    public function getUser(): User;

    public function setUser(User $user): static;
}
