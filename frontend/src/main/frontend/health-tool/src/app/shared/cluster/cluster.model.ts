import { Credentials } from './credentials.model';

export class Cluster {
  id: number;
  name: string = "";
  clusterType: string = "";
  host: string = "";
  secured: boolean;
  http: Credentials = new Credentials();
  ssh: Credentials = new Credentials();
  kerberos: Credentials = new Credentials();
}
