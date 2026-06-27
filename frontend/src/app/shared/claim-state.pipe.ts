import { Pipe, PipeTransform } from '@angular/core';
import { ClaimState } from '../core/models/claim.models';
import { claimStateLabel } from './claim-labels';

@Pipe({ name: 'claimState', standalone: true })
export class ClaimStatePipe implements PipeTransform {
  transform(value: ClaimState): string {
    return claimStateLabel(value);
  }
}
